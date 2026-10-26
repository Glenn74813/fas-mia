package com.miaa.cas.treasury;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentOwnerPolicy;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.journal.NumberingService;
import com.miaa.cas.journal.PostingService;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.treasury.TreasuryDtos.DepositItem;
import com.miaa.cas.treasury.TreasuryDtos.DepositRequest;
import com.miaa.cas.treasury.TreasuryDtos.DepositSummary;
import com.miaa.cas.treasury.TreasuryDtos.DepositView;
import com.miaa.cas.treasury.TreasuryDtos.UndepositedReceipt;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deposit slips: the cashier groups posted collections into a deposit to a bank account; accounting
 * (never the preparer) posts Dr cash in bank / Cr cash on hand. Each collection is deposited once.
 */
@Service
public class DepositService implements DocumentOwnerPolicy {

    public static final String OWNER = "TR_DEPOSIT";
    public static final String SOURCE = "SL_TR_DEPOSIT";

    private final BankAccountService banks;
    private final NumberingService numbering;
    private final PostingService posting;
    private final AccountMappingService mappings;
    private final DocumentService documents;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DepositService(BankAccountService banks, NumberingService numbering, PostingService posting,
            AccountMappingService mappings, DocumentService documents, AuditService audit, NotificationService notifications, JdbcTemplate jdbc, Clock clock) {
        this.banks = banks;
        this.numbering = numbering;
        this.posting = posting;
        this.mappings = mappings;
        this.documents = documents;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UndepositedReceipt> undeposited() {
        return jdbc.query("""
                SELECT r.id, r.receipt_number, c.name, r.receipt_date, r.payment_method, r.payment_reference, r.recorded_by, r.amount
                FROM receipts r JOIN customers c ON c.id = r.customer_id
                WHERE r.status = 'POSTED' AND r.migration_batch_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM deposit_items i WHERE i.receipt_id = r.id AND i.live)
                ORDER BY r.receipt_date, r.receipt_number""", (rs, i) -> new UndepositedReceipt(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getBigDecimal(8)));
    }

    @Transactional(readOnly = true)
    public PageResponse<DepositSummary> list(String status, int page, int size) {
        String filter = status == null || status.isBlank() ? "" : " WHERE d.status = ?";
        Object[] args = filter.isEmpty() ? new Object[0] : new Object[] {status.trim().toUpperCase()};
        return JdbcPaging.query(jdbc, """
                SELECT d.*, b.code AS bank_code, (SELECT count(*) FROM deposit_items i WHERE i.deposit_id = d.id) AS items
                FROM deposits d JOIN bank_accounts b ON b.id = d.bank_account_id""" + filter
                + " ORDER BY d.deposit_date DESC, d.deposit_number DESC, d.id", (rs, i) -> new DepositSummary(
                rs.getObject("id", UUID.class), rs.getString("deposit_number"), rs.getString("bank_code"),
                rs.getDate("deposit_date").toLocalDate(), rs.getString("bank_reference"), rs.getBigDecimal("amount"),
                rs.getInt("items"), rs.getString("status"), rs.getString("prepared_by"),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version")), page, size, args);
    }

    @Transactional(readOnly = true)
    public DepositView get(UUID id) {
        return view(id);
    }

    @Transactional
    public DepositView create(DepositRequest req) {
        CurrentActor.requirePermission("DEPOSIT_PREPARE");
        var bank = banks.requireActive(req.bankAccountId());
        TreeSet<UUID> ids = new TreeSet<>(req.receiptIds());
        if (ids.size() != req.receiptIds().size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Each collection may appear once on a deposit.");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UUID rid : ids) {
            // Locked in id order; the partial unique index is the final guard against double deposit.
            rows.addAll(jdbc.queryForList("SELECT id, receipt_number, receipt_date, status, amount, migration_batch_id FROM receipts "
                    + "WHERE id = ? FOR UPDATE", rid));
        }
        List<String> problems = new ArrayList<>();
        if (rows.size() != ids.size()) {
            problems.add("Some collections were not found.");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            String number = (String) r.get("receipt_number");
            if (!"POSTED".equals(r.get("status"))) {
                problems.add("Receipt " + number + " must be posted before it is deposited.");
            }
            if (r.get("migration_batch_id") != null) {
                problems.add("Receipt " + number + " is a migrated balance; it was deposited in the legacy system.");
            }
            Boolean deposited = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM deposit_items WHERE receipt_id = ? AND live)",
                    Boolean.class, r.get("id"));
            if (Boolean.TRUE.equals(deposited)) {
                problems.add("Receipt " + number + " is already on a deposit.");
            }
            if (((Date) r.get("receipt_date")).toLocalDate().isAfter(req.depositDate())) {
                problems.add("Receipt " + number + " is dated after the deposit.");
            }
            total = total.add((BigDecimal) r.get("amount"));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, problems.get(0), Map.of("violations", problems));
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String number = numbering.next("DEP", req.depositDate().getYear());
        jdbc.update("""
                INSERT INTO deposits (id, deposit_number, bank_account_id, deposit_date, bank_reference, amount, status,
                    prepared_by_id, prepared_by, created_at, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'RECORDED', ?, ?, ?, ?, ?)""", id, number, bank.id(), Date.valueOf(req.depositDate()),
                blank(req.bankReference()), Money.of(total), CurrentActor.userId(), CurrentActor.username(), Timestamp.from(now),
                Timestamp.from(now), CurrentActor.username());
        for (Map<String, Object> r : rows) {
            jdbc.update("INSERT INTO deposit_items (deposit_id, receipt_id, amount) VALUES (?, ?, ?)", id, r.get("id"), r.get("amount"));
        }
        audit.record("DEPOSIT_RECORDED", OWNER, id, Map.of("depositNumber", number, "bank", bank.code(),
                "amount", Money.of(total).toPlainString(), "items", rows.size()));
        return view(id);
    }

    @Transactional
    public DepositView post(UUID id, long version) {
        Map<String, Object> d = lockVersioned(id, version);
        if (!"RECORDED".equals(d.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "POSTED".equals(d.get("status")) ? "The deposit has already been posted."
                    : "Only recorded deposits can be posted.");
        }
        var bank = banks.requireActive((UUID) d.get("bank_account_id"));
        BigDecimal amount = (BigDecimal) d.get("amount");
        String number = (String) d.get("deposit_number");
        String ref = d.get("bank_reference") == null ? number : number + " / " + d.get("bank_reference");
        PostingService.PostedEntry entry = posting.postSubledger(new PostingService.SubledgerEntry(SOURCE, id.toString(),
                ((Date) d.get("deposit_date")).toLocalDate(), "Deposit " + ref + " to " + bank.code(), List.of(
                        new LineData(bank.glAccountId(), null, null, "Deposit " + ref, amount, Money.ZERO),
                        new LineData(mappings.require("CASH_ON_HAND"), null, null, "Collections deposited " + number, Money.ZERO,
                                amount)),
                (UUID) d.get("prepared_by_id"), (String) d.get("prepared_by"), false));
        jdbc.update("UPDATE deposits SET status = 'POSTED', posted_entry_id = ?, version = version + 1, updated_at = ?, "
                + "updated_by = ? WHERE id = ?", entry.entryId(), Timestamp.from(Instant.now(clock)), CurrentActor.username(), id);
        notifications.notify((UUID) d.get("prepared_by_id"), "DOCUMENT_POSTED", "Deposit " + number + " was posted by " + CurrentActor.username(),
                "Journal entry " + entry.entryNumber() + ".", "/treasury/deposits/" + id);
        audit.record("DEPOSIT_POSTED", OWNER, id, Map.of("depositNumber", number, "entryNumber", entry.entryNumber()));
        return view(id);
    }

    @Transactional
    public DepositView cancel(UUID id, long version, String reason) {
        Map<String, Object> d = lockVersioned(id, version);
        if (!"RECORDED".equals(d.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only deposits not yet posted can be cancelled.");
        }
        boolean preparer = CurrentActor.userId().equals(d.get("prepared_by_id")) && CurrentActor.hasPermission("DEPOSIT_PREPARE");
        if (!preparer && !CurrentActor.hasPermission("JOURNAL_POST")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer or accounting can cancel this deposit.");
        }
        jdbc.update("UPDATE deposit_items SET live = FALSE WHERE deposit_id = ?", id);
        jdbc.update("UPDATE deposits SET status = 'CANCELLED', cancel_reason = ?, version = version + 1, updated_at = ?, "
                + "updated_by = ? WHERE id = ?", reason.trim(), Timestamp.from(Instant.now(clock)), CurrentActor.username(), id);
        audit.record("DEPOSIT_CANCELLED", OWNER, id, Map.of("depositNumber", d.get("deposit_number"), "reason", reason.trim()));
        return view(id);
    }

    @Override
    public String ownerType() {
        return OWNER;
    }

    @Override
    public void assertCanModify(UUID ownerId) {
        Map<String, Object> d = row(ownerId);
        CurrentActor.requirePermission("DOCUMENT_UPLOAD");
        if (!CurrentActor.userId().equals(d.get("prepared_by_id"))) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer can change this deposit's attachments.");
        }
        if (!"RECORDED".equals(d.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Attachments can only change before the deposit is posted.");
        }
    }

    @Override
    public void assertCanView(UUID ownerId) {
        row(ownerId);
        if (!CurrentActor.hasPermission("TREASURY_VIEW")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You do not have permission to view treasury records.");
        }
    }

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM deposits WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Deposit");
        }
        return rows.get(0);
    }

    private Map<String, Object> lockVersioned(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM deposits WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Deposit");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        return rows.get(0);
    }

    private DepositView view(UUID id) {
        Map<String, Object> d = row(id);
        Map<String, Object> bank = jdbc.queryForMap("SELECT code, bank_name FROM bank_accounts WHERE id = ?", d.get("bank_account_id"));
        List<DepositItem> items = jdbc.query("""
                SELECT r.id, r.receipt_number, c.name, r.receipt_date, r.payment_method, r.payment_reference, i.amount
                FROM deposit_items i JOIN receipts r ON r.id = i.receipt_id JOIN customers c ON c.id = r.customer_id
                WHERE i.deposit_id = ? ORDER BY r.receipt_number""", (rs, i) -> new DepositItem(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6),
                rs.getBigDecimal(7)), id);
        UUID entryId = (UUID) d.get("posted_entry_id");
        String entryNumber = entryId == null ? null
                : jdbc.queryForObject("SELECT entry_number FROM journal_entries WHERE id = ?", String.class, entryId);
        String status = (String) d.get("status");
        boolean preparer = CurrentActor.userId().equals(d.get("prepared_by_id"));
        List<String> actions = new ArrayList<>();
        if ("RECORDED".equals(status)) {
            if (!preparer && CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("POST");
            }
            if ((preparer && CurrentActor.hasPermission("DEPOSIT_PREPARE")) || CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("CANCEL");
            }
            if (preparer && CurrentActor.hasPermission("DOCUMENT_UPLOAD")) {
                actions.add("UPLOAD");
            }
        }
        return new DepositView(id, (String) d.get("deposit_number"), (UUID) d.get("bank_account_id"), (String) bank.get("code"),
                (String) bank.get("bank_name"), ((Date) d.get("deposit_date")).toLocalDate(), (String) d.get("bank_reference"),
                (BigDecimal) d.get("amount"), status, (String) d.get("prepared_by"), (String) d.get("cancel_reason"), entryId,
                entryNumber, items, documents.list(OWNER, id), actions, ((Number) d.get("version")).longValue());
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static LocalDate date(Object o) {
        return o == null ? null : ((Date) o).toLocalDate();
    }
}
