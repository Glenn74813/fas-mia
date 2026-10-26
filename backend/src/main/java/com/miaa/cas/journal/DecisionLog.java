package com.miaa.cas.journal;

import com.miaa.cas.notifications.NotificationService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Append-only approval decisions (the table rejects UPDATE/DELETE). */
@Component
public class DecisionLog {

    public static final String JOURNAL_VOUCHER = "JOURNAL_VOUCHER";

    public record Decision(UUID id, String action, Integer stepOrder, String stepCode, UUID actorId, String actorUsername,
            String comment, Instant decidedAt) {
    }

    /** Where each document type lives, for telling its preparer about a decision: table, preparer column, number, label, page. */
    private record Target(String table, String preparer, String number, String label, String page) {
    }

    private static final Map<String, Target> TARGETS = Map.of(
            JOURNAL_VOUCHER, new Target("journal_vouchers", "prepared_by_id", "COALESCE(voucher_number, description)", "Journal voucher", "/journal-vouchers/"),
            "AP_INVOICE", new Target("supplier_invoices", "prepared_by_id", "invoice_number", "Supplier invoice", "/payables/invoices/"),
            "DISBURSEMENT_VOUCHER", new Target("disbursement_vouchers", "prepared_by_id", "dv_number", "Disbursement voucher",
                    "/payables/disbursements/"),
            "AR_INVOICE", new Target("billing_documents", "prepared_by_id", "COALESCE(document_number, description)", "Billing", "/receivables/billings/"),
            "AR_MEMO", new Target("billing_documents", "prepared_by_id", "COALESCE(document_number, description)", "Billing memo", "/receivables/billings/"),
            "BUDGET", new Target("budgets", "prepared_by_id", "name", "Budget", "/budgets/"),
            "BANK_RECONCILIATION", new Target("bank_reconciliations", "prepared_by_id", "'as of ' || as_of_date", "Bank reconciliation",
                    "/treasury/reconciliations/"));

    /** Decisions the preparer hears about, as the verb used in the notification. */
    private static final Map<String, String> OUTCOMES = Map.of("RETURN", "returned", "REJECT", "rejected", "POST", "posted",
            "CONFIRM", "paid", "VOID", "payment voided");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final NotificationService notifications;

    public DecisionLog(JdbcTemplate jdbc, Clock clock, NotificationService notifications) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.notifications = notifications;
    }

    public void record(UUID voucherId, String action, Integer stepOrder, String stepCode, UUID actorId, String actor,
            String comment) {
        record(JOURNAL_VOUCHER, voucherId, action, stepOrder, stepCode, actorId, actor, comment);
    }

    public void record(String documentType, UUID documentId, String action, Integer stepOrder, String stepCode,
            UUID actorId, String actor, String comment) {
        jdbc.update("""
                INSERT INTO approval_decisions (id, document_type, document_id, action, step_order, step_code, actor_id,
                    actor_username, comment, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), documentType, documentId, action, stepOrder, stepCode, actorId, actor, comment,
                Timestamp.from(Instant.now(clock)));
        notifyPreparer(documentType, documentId, action, stepCode, actor, comment);
    }

    /** Tells the preparer, in the same transaction, that someone else approved, returned, rejected, paid or posted their document. */
    private void notifyPreparer(String documentType, UUID documentId, String action, String stepCode, String actor, String comment) {
        Target t = TARGETS.get(documentType);
        String verb = "APPROVE".equals(action) ? (stepCode == null ? "approved" : pastTense(stepCode)) : OUTCOMES.get(action);
        if (t == null || verb == null) {
            return;
        }
        Map<String, Object> doc = jdbc.queryForMap("SELECT " + t.preparer() + " AS preparer, " + t.number() + " AS number"
                + (JOURNAL_VOUCHER.equals(documentType) ? ", source_type" : "") + " FROM " + t.table() + " WHERE id = ?", documentId);
        if (String.valueOf(doc.get("source_type")).startsWith("SL_")) {
            return; // the ledger entry of a sub-ledger document: its preparer hears about the document itself
        }
        String number = doc.get("number") == null ? "" : " " + doc.get("number");
        String category = "POST".equals(action) ? "DOCUMENT_POSTED" : "DOCUMENT_" + action;
        notifications.notify((UUID) doc.get("preparer"), category, t.label() + number + " was " + verb + " by " + actor,
                "POST".equals(action) && comment != null ? "Journal entry " + comment + "." : comment, t.page() + documentId);
    }

    private static String pastTense(String stepCode) {
        String s = stepCode.toLowerCase().replace('_', ' ');
        return s.endsWith("y") ? s.substring(0, s.length() - 1) + "ied" : s.endsWith("e") ? s + "d" : s + "ed";
    }

    public List<Decision> history(UUID voucherId) {
        return history(JOURNAL_VOUCHER, voucherId);
    }

    public List<Decision> history(String documentType, UUID documentId) {
        return jdbc.query("""
                SELECT id, action, step_order, step_code, actor_id, actor_username, comment, decided_at
                FROM approval_decisions WHERE document_type = ? AND document_id = ? ORDER BY decided_at, id""",
                (rs, i) -> new Decision(rs.getObject(1, UUID.class), rs.getString(2), (Integer) rs.getObject(3),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7),
                        rs.getTimestamp(8).toInstant()), documentType, documentId);
    }

    /** Actors who approved a step since the most recent submission (one approval cycle). */
    public List<UUID> approversInCurrentCycle(UUID voucherId) {
        return approversInCurrentCycle(JOURNAL_VOUCHER, voucherId);
    }

    public List<UUID> approversInCurrentCycle(String documentType, UUID documentId) {
        List<Decision> all = history(documentType, documentId);
        int lastSubmit = -1;
        for (int i = 0; i < all.size(); i++) {
            if ("SUBMIT".equals(all.get(i).action())) {
                lastSubmit = i;
            }
        }
        return all.subList(lastSubmit + 1, all.size()).stream().filter(d -> "APPROVE".equals(d.action()))
                .map(Decision::actorId).toList();
    }
}
