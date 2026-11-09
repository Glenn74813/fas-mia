package com.miaa.cas.integrations.payroll;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.integrations.payroll.PayrollDtos.ColumnMapping;
import com.miaa.cas.integrations.payroll.PayrollDtos.ColumnMappingRequest;
import com.miaa.cas.integrations.payroll.PayrollDtos.ColumnTotal;
import com.miaa.cas.integrations.payroll.PayrollDtos.ImportResult;
import com.miaa.cas.integrations.payroll.PayrollDtos.ImportView;
import com.miaa.cas.integrations.payroll.PayrollDtos.Preview;
import com.miaa.cas.integrations.payroll.PayrollDtos.PreviewLine;
import com.miaa.cas.journal.DecisionLog;
import com.miaa.cas.journal.JournalVoucherService;
import com.miaa.cas.journal.web.JournalDtos.LineRequest;
import com.miaa.cas.journal.web.JournalDtos.VoucherRequest;
import com.miaa.cas.security.CurrentActor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Books a General Payroll exported by the HRIS (TOR §4.6.2, Annex D; C-37): one row per employee, one column per
 * earning or deduction. Each column is booked through {@code payroll_column_mappings}, which the client maintains: its
 * total is debited or credited to the mapped account, or, on an account that keeps an employee subsidiary ledger,
 * booked per employee against the subsidiary account whose code is the employee number. A preview shows the voucher and
 * every problem without saving anything; the import creates one draft journal voucher, with the file attached, that
 * goes through the normal approval and posting. The same file cannot be imported twice.
 */
@Service
public class GeneralPayrollService {

    /** A journal voucher holds at most 500 lines. */
    static final int MAX_LINES = 500;
    private static final Set<String> BOOKED = Set.of("DEBIT", "CREDIT");

    private record Account(UUID id, String code, String name, String subsidiaryType, boolean requiresCostCenter, boolean usable) {
    }

    private record Table(List<String> headers, List<List<String>> rows) {
    }

    /** The voucher a file would create, with its problems. */
    private record Built(Preview preview, List<LineRequest> lines, String sha256) {
    }

    private final JdbcTemplate jdbc;
    private final JournalVoucherService vouchers;
    private final DocumentService documents;
    private final AuditService audit;
    private final Clock clock;

    public GeneralPayrollService(JdbcTemplate jdbc, JournalVoucherService vouchers, DocumentService documents, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.vouchers = vouchers;
        this.documents = documents;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- column mapping

    @Transactional(readOnly = true)
    public List<ColumnMapping> mappings() {
        return jdbc.query("""
                SELECT m.*, a.code, a.name, a.subsidiary_type FROM payroll_column_mappings m
                LEFT JOIN chart_of_accounts a ON a.id = m.account_id ORDER BY m.label""", (rs, i) -> new ColumnMapping(
                rs.getString("column_key"), rs.getString("label"), rs.getString("treatment"), rs.getObject("account_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("subsidiary_type"), rs.getLong("version"),
                rs.getString("updated_by"), rs.getTimestamp("updated_at").toInstant()));
    }

    /** Adds a column (matched by its heading) or changes how an existing one is booked. */
    @Transactional
    public ColumnMapping saveMapping(ColumnMappingRequest req) {
        CurrentActor.requirePermission("ACCOUNT_ADMINISTER");
        String key = key(req.label());
        boolean booked = BOOKED.contains(req.treatment());
        if (booked && req.accountId() == null) {
            throw invalid("Choose the account the column \"" + req.label().trim() + "\" is booked to.");
        }
        if (booked) {
            Account a = accounts(Set.of(req.accountId())).get(req.accountId());
            if (a == null || !a.usable()) {
                throw invalid("Choose an active, postable account.");
            }
        }
        UUID account = booked ? req.accountId() : null;
        Timestamp now = Timestamp.from(Instant.now(clock));
        List<Long> existing = jdbc.queryForList("SELECT version FROM payroll_column_mappings WHERE column_key = ? FOR UPDATE", Long.class, key);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO payroll_column_mappings (column_key, label, treatment, account_id, updated_at, updated_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", key, req.label().trim(), req.treatment(), account, now, CurrentActor.username());
        } else {
            if (req.version() == null || existing.get(0) != req.version().longValue()) {
                throw ApiException.staleVersion();
            }
            jdbc.update("UPDATE payroll_column_mappings SET label = ?, treatment = ?, account_id = ?, version = version + 1, updated_at = ?, "
                    + "updated_by = ? WHERE column_key = ?", req.label().trim(), req.treatment(), account, now, CurrentActor.username(), key);
        }
        audit.record("PAYROLL_COLUMN_MAPPING_SET", "PAYROLL_COLUMN", key, Map.of("treatment", req.treatment(),
                "account", account == null ? "none" : account.toString()));
        return mappings().stream().filter(m -> m.columnKey().equals(key)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- preview and import

    @Transactional(readOnly = true)
    public Preview preview(String fileName, byte[] file, LocalDate accountingDate, UUID costCenterId) {
        CurrentActor.requirePermission("JOURNAL_CREATE");
        return build(fileName, file, accountingDate, costCenterId).preview();
    }

    @Transactional
    public ImportResult importFile(String fileName, byte[] file, LocalDate accountingDate, UUID costCenterId, String reference) {
        CurrentActor.requirePermission("JOURNAL_CREATE");
        if (accountingDate == null) {
            throw invalid("Give the accounting date of the payroll.");
        }
        Built b = build(fileName, file, accountingDate, costCenterId);
        if (!b.preview().problems().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The General Payroll cannot be imported yet.",
                    Map.of("violations", b.preview().problems()));
        }
        String ref = reference == null || reference.isBlank() ? baseName(fileName) : reference.trim();
        ref = ref.length() > 60 ? ref.substring(0, 60) : ref;
        String description = "General payroll " + ref + " (" + b.preview().employees() + " employees)";
        UUID voucherId = vouchers.create(new VoucherRequest("STANDARD", accountingDate, false, description,
                ("General payroll " + ref).substring(0, Math.min(200, ("General payroll " + ref).length())), b.lines())).id();
        documents.upload(DecisionLog.JOURNAL_VOUCHER, voucherId, safeName(fileName), file);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payroll_imports (id, reference, file_name, file_sha256, employees, total_amount, journal_voucher_id,
                    imported_by_id, imported_by, imported_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, ref, safeName(fileName),
                b.sha256(), b.preview().employees(), b.preview().totalDebit(), voucherId, CurrentActor.userId(), CurrentActor.username(),
                Timestamp.from(Instant.now(clock)));
        audit.record("PAYROLL_GENERAL_IMPORTED", "JOURNAL_VOUCHER", voucherId, Map.of("reference", ref, "employees",
                b.preview().employees(), "total", b.preview().totalDebit().toPlainString(), "sha256", b.sha256()));
        return new ImportResult(voucherId, description, b.preview().employees(), b.preview().totalDebit());
    }

    @Transactional(readOnly = true)
    public List<ImportView> imports() {
        return jdbc.query("""
                SELECT p.*, v.voucher_number, v.status FROM payroll_imports p JOIN journal_vouchers v ON v.id = p.journal_voucher_id
                ORDER BY p.imported_at DESC LIMIT 100""", (rs, i) -> new ImportView(rs.getObject("id", UUID.class), rs.getString("reference"),
                rs.getString("file_name"), rs.getInt("employees"), rs.getBigDecimal("total_amount"), rs.getObject("journal_voucher_id", UUID.class),
                rs.getString("voucher_number"), rs.getString("status"), rs.getString("imported_by"), rs.getTimestamp("imported_at").toInstant()));
    }

    private Built build(String fileName, byte[] file, LocalDate accountingDate, UUID costCenterId) {
        if (file == null || file.length == 0) {
            throw invalid("Choose the General Payroll file (Excel .xlsx or CSV).");
        }
        String sha = sha256(file);
        Table table = read(fileName, file);
        List<String> problems = new ArrayList<>();
        Map<String, ColumnMapping> byKey = new HashMap<>();
        mappings().forEach(m -> byKey.put(m.columnKey(), m));

        // Columns: every heading must be in the mapping, as booked or ignored.
        List<ColumnMapping> cols = new ArrayList<>();
        int employeeCol = -1;
        for (int i = 0; i < table.headers().size(); i++) {
            String h = table.headers().get(i);
            if (h.isBlank()) {
                cols.add(null);
                continue;
            }
            ColumnMapping m = byKey.get(key(h));
            if (m == null) {
                problems.add("Column \"" + h.trim() + "\" is not in the column mapping. Add it under Column mapping, booked or ignored.");
            } else if (BOOKED.contains(m.treatment()) && m.accountId() == null) {
                problems.add("Column \"" + m.label() + "\" has no account yet. Set it under Column mapping.");
            }
            if (m != null && "EMPLOYEE_NUMBER".equals(m.treatment())) {
                employeeCol = i;
            }
            cols.add(m);
        }
        if (employeeCol < 0) {
            problems.add("The file has no employee number column (a column mapped as Employee number).");
        }

        // Rows: one per employee; a row without an employee number is allowed only as a totals row.
        Map<String, BigDecimal> columnTotals = new LinkedHashMap<>();
        Map<String, Map<Integer, BigDecimal>> perEmployee = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int employees = 0;
        for (int r = 0; r < table.rows().size(); r++) {
            List<String> row = table.rows().get(r);
            int rowNo = r + 2;
            String emp = employeeCol < 0 || employeeCol >= row.size() ? "" : row.get(employeeCol).trim();
            if (emp.isEmpty()) {
                if (row.stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("total"))) {
                    continue;
                }
                problems.add("Row " + rowNo + " has no employee number.");
                continue;
            }
            if (!seen.add(emp)) {
                problems.add("Employee " + emp + " appears more than once (row " + rowNo + ").");
                continue;
            }
            employees++;
            Map<Integer, BigDecimal> amounts = new HashMap<>();
            for (int c = 0; c < cols.size(); c++) {
                ColumnMapping m = cols.get(c);
                if (m == null || !BOOKED.contains(m.treatment())) {
                    continue;
                }
                String raw = c < row.size() ? row.get(c).trim().replace(",", "") : "";
                BigDecimal v;
                try {
                    v = raw.isEmpty() ? BigDecimal.ZERO : new BigDecimal(raw).setScale(2, RoundingMode.HALF_EVEN);
                } catch (NumberFormatException e) {
                    problems.add("Row " + rowNo + ", " + m.label() + ": \"" + raw + "\" is not an amount.");
                    continue;
                }
                if (v.signum() < 0) {
                    problems.add("Row " + rowNo + ", " + m.label() + ": negative amounts are not booked; correct the payroll.");
                    continue;
                }
                amounts.put(c, v);
                columnTotals.merge(m.columnKey(), v, BigDecimal::add);
            }
            perEmployee.put(emp, amounts);
        }
        if (employees == 0 && employeeCol >= 0) {
            problems.add("The file has no employee rows.");
        }

        // Lines: one per account and side, or per employee on an account with an employee subsidiary ledger.
        Set<UUID> accountIds = new LinkedHashSet<>();
        cols.stream().filter(m -> m != null && m.accountId() != null && BOOKED.contains(m.treatment())).forEach(m -> accountIds.add(m.accountId()));
        Map<UUID, Account> accounts = accounts(accountIds);
        Map<String, UUID> employeeSubsidiaries = employeeSubsidiaries();
        record Key(UUID account, String side, String employee) {
        }
        Map<Key, BigDecimal> sums = new LinkedHashMap<>();
        Map<Key, Set<String>> labels = new HashMap<>();
        Set<String> missingEmployees = new LinkedHashSet<>();
        Set<String> reported = new HashSet<>();
        for (Map.Entry<String, Map<Integer, BigDecimal>> e : perEmployee.entrySet()) {
            for (Map.Entry<Integer, BigDecimal> a : e.getValue().entrySet()) {
                ColumnMapping m = cols.get(a.getKey());
                Account acc = accounts.get(m.accountId());
                if (acc == null || a.getValue().signum() == 0) {
                    continue;
                }
                if (!acc.usable() && reported.add("unusable " + acc.code())) {
                    problems.add("Account " + acc.code() + " (" + m.label() + ") is inactive or not postable.");
                }
                String sl = acc.subsidiaryType();
                if (sl != null && !"EMPLOYEE".equals(sl)) {
                    if (reported.add("sl " + acc.code())) {
                        problems.add("Account " + acc.code() + " (" + m.label() + ") needs a " + sl.toLowerCase(Locale.ROOT)
                                + " subsidiary, which a payroll cannot name. Map the column to another account.");
                    }
                    continue;
                }
                boolean perEmp = "EMPLOYEE".equals(sl);
                if (perEmp && !employeeSubsidiaries.containsKey(e.getKey())) {
                    missingEmployees.add(e.getKey());
                }
                Key k = new Key(acc.id(), m.treatment(), perEmp ? e.getKey() : null);
                sums.merge(k, a.getValue(), BigDecimal::add);
                labels.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(m.label());
            }
        }
        if (!missingEmployees.isEmpty()) {
            List<String> list = new ArrayList<>(missingEmployees);
            problems.add("No active employee subsidiary account with code " + String.join(", ", list.subList(0, Math.min(20, list.size())))
                    + (list.size() > 20 ? " and " + (list.size() - 20) + " more" : "") + ". Add them under General ledger, Subsidiary accounts, "
                    + "with the employee number as the code.");
        }
        List<PreviewLine> preview = new ArrayList<>();
        List<LineRequest> lines = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        Set<String> needCostCentre = new LinkedHashSet<>();
        for (Map.Entry<Key, BigDecimal> s : sums.entrySet()) {
            Key k = s.getKey();
            Account acc = accounts.get(k.account());
            BigDecimal amount = Money.of(s.getValue());
            String desc = String.join(", ", labels.get(k)) + (k.employee() == null ? "" : ", employee " + k.employee());
            desc = desc.length() > 500 ? desc.substring(0, 500) : desc;
            if (acc.requiresCostCenter() && costCenterId == null) {
                needCostCentre.add(acc.code());
            }
            boolean dr = "DEBIT".equals(k.side());
            debit = dr ? debit.add(amount) : debit;
            credit = dr ? credit : credit.add(amount);
            preview.add(new PreviewLine(acc.code(), acc.name(), k.side(), amount, k.employee(), desc));
            lines.add(new LineRequest(acc.id(), acc.requiresCostCenter() ? costCenterId : null,
                    k.employee() == null ? null : employeeSubsidiaries.get(k.employee()), desc, dr ? amount : Money.ZERO, dr ? Money.ZERO : amount));
        }
        if (!needCostCentre.isEmpty()) {
            problems.add("Choose a cost centre: " + (needCostCentre.size() == 1 ? "account " : "accounts ") + String.join(", ", needCostCentre)
                    + (needCostCentre.size() == 1 ? " requires one." : " require one."));
        }
        if (debit.compareTo(credit) != 0) {
            problems.add("Debits " + debit.toPlainString() + " and credits " + credit.toPlainString() + " differ by "
                    + debit.subtract(credit).abs().toPlainString() + ". Check that every earning and every deduction column is booked.");
        }
        if (lines.size() > MAX_LINES) {
            problems.add("The voucher would have " + lines.size() + " lines; a journal voucher holds at most " + MAX_LINES + ".");
        }
        if (accountingDate != null && !jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM accounting_periods WHERE ? BETWEEN start_date AND end_date)",
                Boolean.class, java.sql.Date.valueOf(accountingDate))) {
            problems.add("No accounting period covers " + accountingDate + ".");
        }
        List<String> done = jdbc.queryForList("""
                SELECT coalesce(v.voucher_number, 'a draft voucher') FROM payroll_imports p JOIN journal_vouchers v ON v.id = p.journal_voucher_id
                WHERE p.file_sha256 = ? AND v.status NOT IN ('CANCELLED', 'REJECTED')""", String.class, sha);
        if (!done.isEmpty()) {
            problems.add("This file was already imported as " + done.get(0) + ".");
        }
        List<ColumnTotal> totals = new ArrayList<>();
        for (ColumnMapping m : cols) {
            if (m != null && BOOKED.contains(m.treatment())) {
                totals.add(new ColumnTotal(m.label(), m.treatment(), m.accountCode(), Money.of(columnTotals.getOrDefault(m.columnKey(), BigDecimal.ZERO))));
            }
        }
        return new Built(new Preview(safeName(fileName), employees, totals, preview, Money.of(debit), Money.of(credit), problems,
                done.isEmpty() ? null : done.get(0)), lines, sha);
    }

    // ---------------------------------------------------------------- reading the file

    private static Table read(String fileName, byte[] file) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) {
            return csv(new String(file, StandardCharsets.UTF_8));
        }
        if (!name.endsWith(".xlsx")) {
            throw invalid("Upload the General Payroll as an Excel (.xlsx) or CSV file.");
        }
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            List<String> headers = null;
            List<List<String>> rows = new ArrayList<>();
            for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                    values.add(text(row.getCell(c), fmt));
                }
                if (values.stream().allMatch(String::isBlank)) {
                    continue;
                }
                if (headers == null) {
                    headers = values;
                } else {
                    rows.add(values);
                }
            }
            if (headers == null) {
                throw invalid("The file is empty.");
            }
            return new Table(headers, rows);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) {
                throw a;
            }
            throw invalid("The file could not be read as an Excel workbook.");
        }
    }

    /** A cell as text; numbers in plain decimal notation so amounts parse exactly. */
    private static String text(Cell c, DataFormatter fmt) {
        if (c == null) {
            return "";
        }
        CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        if (t == CellType.NUMERIC) {
            return new BigDecimal(Double.toString(c.getNumericCellValue())).toPlainString();
        }
        if (t == CellType.STRING) {
            return c.getStringCellValue().trim();
        }
        return t == CellType.FORMULA ? "" : fmt.formatCellValue(c).trim();
    }

    /** RFC 4180 CSV: quoted fields may hold commas, quotes ("") and line breaks. */
    static Table csv(String content) {
        List<List<String>> all = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        String s = content.startsWith("﻿") ? content.substring(1) : content;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    f.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(f.toString().trim());
                f.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(f.toString().trim());
                f.setLength(0);
                if (!row.stream().allMatch(String::isBlank)) {
                    all.add(row);
                }
                row = new ArrayList<>();
            } else {
                f.append(ch);
            }
        }
        row.add(f.toString().trim());
        if (!row.stream().allMatch(String::isBlank)) {
            all.add(row);
        }
        if (all.isEmpty()) {
            throw invalid("The file is empty.");
        }
        return new Table(all.get(0), all.subList(1, all.size()));
    }

    // ---------------------------------------------------------------- helpers

    /** The heading as matched: lower case, single spaces. */
    static String key(String heading) {
        return heading == null ? "" : heading.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private Map<UUID, Account> accounts(Set<UUID> ids) {
        Map<UUID, Account> out = new HashMap<>();
        for (UUID id : ids) {
            jdbc.query("SELECT id, code, name, subsidiary_type, requires_cost_center, postable AND status = 'ACTIVE' AS usable "
                    + "FROM chart_of_accounts WHERE id = ?", rs -> {
                out.put(id, new Account(id, rs.getString("code"), rs.getString("name"), rs.getString("subsidiary_type"),
                        rs.getBoolean("requires_cost_center"), rs.getBoolean("usable")));
            }, id);
        }
        return out;
    }

    private Map<String, UUID> employeeSubsidiaries() {
        Map<String, UUID> out = new HashMap<>();
        jdbc.query("SELECT code, id FROM subsidiary_ledger_accounts WHERE sl_type = 'EMPLOYEE' AND status = 'ACTIVE'",
                rs -> { out.put(rs.getString("code"), rs.getObject("id", UUID.class)); });
        return out;
    }

    private static String safeName(String fileName) {
        String n = fileName == null || fileName.isBlank() ? "general-payroll.xlsx" : fileName.replaceAll("[\\\\/]", "_").trim();
        return n.length() > 255 ? n.substring(n.length() - 255) : n;
    }

    private static String baseName(String fileName) {
        String n = safeName(fileName);
        return n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, Map.of("violations", List.of(message)));
    }
}
