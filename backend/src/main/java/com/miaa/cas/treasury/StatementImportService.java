package com.miaa.cas.treasury;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.treasury.TreasuryDtos.StatementLineView;
import com.miaa.cas.treasury.TreasuryDtos.StatementSummary;
import com.miaa.cas.treasury.TreasuryDtos.StatementView;
import com.miaa.cas.treasury.statements.Camt053Parser;
import com.miaa.cas.treasury.statements.CsvStatementParser;
import com.miaa.cas.treasury.statements.Mt940Parser;
import com.miaa.cas.treasury.statements.ParsedStatement;
import com.miaa.cas.treasury.statements.StatementFormatException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports bank statements (CSV, MT940, camt.053) into a bank account. A statement is accepted only
 * when its lines reconcile its own opening and closing balances, it is for the right account and
 * currency, and it neither repeats a file nor overlaps a period already imported.
 */
@Service
public class StatementImportService {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_LINES = 20_000;

    private final BankAccountService banks;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public StatementImportService(BankAccountService banks, AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.banks = banks;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public StatementView importStatement(UUID bankAccountId, String format, String fileName, byte[] content,
            BigDecimal opening, BigDecimal closing) {
        CurrentActor.requirePermission("BANK_STATEMENT_IMPORT");
        var bank = banks.requireActive(bankAccountId);
        if (content.length == 0 || content.length > MAX_BYTES) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "Statement files must be between 1 byte and 5 MB.");
        }
        String fmt = format == null ? "" : format.trim().toUpperCase();
        ParsedStatement s;
        try {
            s = switch (fmt) {
                case "CSV" -> CsvStatementParser.parse(content, opening == null ? null : Money.of(opening),
                        closing == null ? null : Money.of(closing));
                case "MT940" -> Mt940Parser.parse(content);
                case "CAMT053" -> Camt053Parser.parse(content);
                default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "format must be CSV, MT940 or CAMT053.");
            };
        } catch (StatementFormatException e) {
            throw rejected(e.getMessage());
        }
        List<String> problems = new ArrayList<>();
        if (s.currency() != null && !"PHP".equals(s.currency())) {
            problems.add("The statement is in " + s.currency() + "; only PHP accounts are supported (C-21).");
        }
        if (s.accountIdentifier() != null) {
            String digits = s.accountIdentifier().replaceAll("\\D", "");
            if (!digits.endsWith(bank.accountNumberLast4())) {
                problems.add("The statement's account does not end in " + bank.accountNumberLast4() + " (bank account " + bank.code() + ").");
            }
        }
        if (s.lines().size() > MAX_LINES) {
            problems.add("More than " + MAX_LINES + " lines; split the statement.");
        }
        BigDecimal sum = s.lines().stream().map(ParsedStatement.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (s.openingBalance().add(sum).compareTo(s.closingBalance()) != 0) {
            problems.add("Opening balance " + s.openingBalance().toPlainString() + " plus transactions " + sum.toPlainString()
                    + " does not equal the closing balance " + s.closingBalance().toPlainString() + ".");
        }
        s.lines().stream().filter(l -> l.valueDate().isBefore(s.periodStart()) || l.valueDate().isAfter(s.periodEnd()))
                .findFirst().ifPresent(l -> problems.add("A transaction dated " + l.valueDate() + " is outside the statement period."));
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, problems.get(0), Map.of("violations", problems));
        }
        String hash = sha256(content);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bank_statements WHERE bank_account_id = ? "
                + "AND file_sha256 = ?)", Boolean.class, bank.id(), hash))) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "This file has already been imported for " + bank.code() + ".");
        }
        // Serialise imports per account so overlap checks cannot race.
        jdbc.queryForObject("SELECT id FROM bank_accounts WHERE id = ? FOR UPDATE", UUID.class, bank.id());
        List<String> overlap = jdbc.queryForList("SELECT period_start || ' to ' || period_end FROM bank_statements "
                + "WHERE bank_account_id = ? AND period_start <= ? AND period_end >= ?", String.class, bank.id(),
                Date.valueOf(s.periodEnd()), Date.valueOf(s.periodStart()));
        if (!overlap.isEmpty()) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A statement for " + overlap.get(0)
                    + " is already imported for this account; periods may not overlap.");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO bank_statements (id, bank_account_id, format, statement_reference, period_start, period_end,
                    opening_balance, closing_balance, file_name, file_sha256, line_count, imported_at, imported_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, bank.id(), fmt, cut(s.reference(), 60),
                Date.valueOf(s.periodStart()), Date.valueOf(s.periodEnd()), s.openingBalance(), s.closingBalance(),
                cut(safeName(fileName), 255), hash, s.lines().size(), Timestamp.from(now), CurrentActor.username());
        int n = 1;
        for (ParsedStatement.Line l : s.lines()) {
            jdbc.update("INSERT INTO bank_statement_lines (id, statement_id, line_no, value_date, booking_date, amount, description, "
                    + "bank_reference) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), id, n++, Date.valueOf(l.valueDate()),
                    l.bookingDate() == null ? null : Date.valueOf(l.bookingDate()), l.amount(), l.description(), l.reference());
        }
        audit.record("BANK_STATEMENT_IMPORTED", "BANK_STATEMENT", id, Map.of("bank", bank.code(), "format", fmt,
                "period", s.periodStart() + ".." + s.periodEnd(), "lines", s.lines().size(), "sha256", hash));
        return get(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<StatementSummary> list(UUID bankAccountId, int page, int size) {
        return JdbcPaging.query(jdbc, SUMMARY_SQL + " WHERE (?::uuid IS NULL OR s.bank_account_id = ?::uuid) ORDER BY b.code, s.period_end DESC, s.id",
                (rs, i) -> summary(rs), page, size, bankAccountId, bankAccountId);
    }

    @Transactional(readOnly = true)
    public StatementView get(UUID id) {
        List<StatementSummary> rows = jdbc.query(SUMMARY_SQL + " WHERE s.id = ?", (rs, i) -> summary(rs), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Bank statement");
        }
        StatementSummary s = rows.get(0);
        List<String> warnings = new ArrayList<>();
        List<BigDecimal> previous = jdbc.queryForList("SELECT closing_balance FROM bank_statements WHERE bank_account_id = ? "
                + "AND period_end < ? ORDER BY period_end DESC LIMIT 1", BigDecimal.class, s.bankAccountId(), Date.valueOf(s.periodStart()));
        if (!previous.isEmpty() && previous.get(0).compareTo(s.openingBalance()) != 0) {
            warnings.add("Opening balance " + s.openingBalance().toPlainString() + " differs from the previous statement's closing "
                    + "balance " + previous.get(0).toPlainString() + "; a statement may be missing.");
        }
        return new StatementView(s, lines("l.statement_id = ?", id), warnings);
    }

    List<StatementLineView> lines(String where, Object... args) {
        return jdbc.query("SELECT l.* FROM bank_statement_lines l JOIN bank_statements s ON s.id = l.statement_id WHERE " + where
                + " ORDER BY l.value_date, s.period_start, l.line_no", (rs, i) -> new StatementLineView(rs.getObject("id", UUID.class),
                rs.getInt("line_no"), rs.getDate("value_date").toLocalDate(), DepositService.date(rs.getDate("booking_date")),
                rs.getBigDecimal("amount"), rs.getString("description"), rs.getString("bank_reference"),
                rs.getObject("match_id", UUID.class)), args);
    }

    private static final String SUMMARY_SQL = """
            SELECT s.*, b.code AS bank_code,
                   (SELECT count(*) FROM bank_statement_lines l WHERE l.statement_id = s.id AND l.match_id IS NULL) AS unmatched,
                   r.id AS recon_id, r.status AS recon_status
            FROM bank_statements s JOIN bank_accounts b ON b.id = s.bank_account_id
            LEFT JOIN bank_reconciliations r ON r.statement_id = s.id""";

    private static StatementSummary summary(ResultSet rs) throws SQLException {
        return new StatementSummary(rs.getObject("id", UUID.class), rs.getObject("bank_account_id", UUID.class),
                rs.getString("bank_code"), rs.getString("format"), rs.getString("statement_reference"),
                rs.getDate("period_start").toLocalDate(), rs.getDate("period_end").toLocalDate(), rs.getBigDecimal("opening_balance"),
                rs.getBigDecimal("closing_balance"), rs.getInt("line_count"), rs.getInt("unmatched"), rs.getString("file_name"),
                rs.getTimestamp("imported_at").toInstant(), rs.getString("imported_by"), rs.getObject("recon_id", UUID.class),
                rs.getString("recon_status"));
    }

    private static ApiException rejected(String message) {
        return new ApiException(ErrorCode.FILE_REJECTED, "Statement not imported: " + message);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "statement";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.replaceAll("[^A-Za-z0-9._ -]", "_");
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
