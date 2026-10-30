package com.miaa.cas.integrations.bir;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.integrations.TransmissionService;
import com.miaa.cas.integrations.TransmissionService.TransmissionView;
import com.miaa.cas.security.CurrentActor;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the CAS canonical sales document for posted billing documents and receipts and queues it on
 * the BIR EIS channel. The canonical form carries everything CAS knows about the document; it is
 * deliberately labelled as not being the EIS schema, which is mapped once BIR publishes it (C-06).
 */
@Service
public class EisDocumentService {

    public static final String SCHEMA = "CAS-CANONICAL-SALES-DOCUMENT";
    public static final String SCHEMA_VERSION = "0.1";

    private final TransmissionService transmissions;
    private final JdbcTemplate jdbc;
    private final String sellerName;
    private final String sellerTin;

    public EisDocumentService(TransmissionService transmissions, JdbcTemplate jdbc,
            @Value("${cas.organization.name:Avtech Information Technology Solutions, Inc.}") String sellerName,
            @Value("${cas.integrations.bir-eis.seller-tin:}") String sellerTin) {
        this.transmissions = transmissions;
        this.jdbc = jdbc;
        this.sellerName = sellerName;
        this.sellerTin = sellerTin;
    }

    @Transactional
    public TransmissionView queue(String documentType, UUID documentId) {
        CurrentActor.requirePermission("INTEGRATION_MANAGE");
        Map<String, Object> payload = switch (documentType) {
            case "AR_BILLING" -> billing(documentId);
            case "AR_RECEIPT" -> receipt(documentId);
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "documentType must be AR_BILLING or AR_RECEIPT.");
        };
        return transmissions.enqueue(BirEisAdapter.CHANNEL, documentType, documentId, documentType + ":" + documentId, payload);
    }

    /** Queues every posted billing document and receipt dated in the range that is not queued yet. */
    @Transactional
    public int queuePosted(LocalDate from, LocalDate to) {
        CurrentActor.requirePermission("INTEGRATION_MANAGE");
        List<Object[]> docs = new ArrayList<>();
        jdbc.query("""
                SELECT 'AR_BILLING', d.id FROM billing_documents d WHERE d.status = 'POSTED' AND d.document_date BETWEEN ? AND ?
                UNION ALL SELECT 'AR_RECEIPT', r.id FROM receipts r WHERE r.status = 'POSTED' AND r.receipt_date BETWEEN ? AND ?""",
                rs -> { docs.add(new Object[] {rs.getString(1), rs.getObject(2, UUID.class)}); },
                Date.valueOf(from), Date.valueOf(to), Date.valueOf(from), Date.valueOf(to));
        int queued = 0;
        for (Object[] d : docs) {
            Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM integration_transmissions WHERE channel = ? AND idempotency_key = ?)",
                    Boolean.class, BirEisAdapter.CHANNEL, d[0] + ":" + d[1]);
            if (!Boolean.TRUE.equals(exists)) {
                queue((String) d[0], (UUID) d[1]);
                queued++;
            }
        }
        return queued;
    }

    private Map<String, Object> billing(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.*, c.code AS c_code, c.name AS c_name, c.tin AS c_tin, c.address AS c_address, rel.document_number AS related_number,
                       t.code AS vat_code, t.rate AS vat_rate
                FROM billing_documents d JOIN customers c ON c.id = d.customer_id LEFT JOIN billing_documents rel ON rel.id = d.related_invoice_id
                LEFT JOIN tax_codes t ON t.id = d.vat_code_id WHERE d.id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Billing document");
        }
        Map<String, Object> d = rows.get(0);
        if (!"POSTED".equals(d.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only posted documents are transmitted.");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        jdbc.query("SELECT l.line_no, l.description, l.net_amount, a.code FROM billing_document_lines l JOIN chart_of_accounts a "
                + "ON a.id = l.revenue_account_id WHERE l.document_id = ? ORDER BY l.line_no", rs -> {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("lineNo", rs.getInt(1));
            l.put("description", rs.getString(2));
            l.put("netAmount", rs.getBigDecimal(3).toPlainString());
            l.put("revenueAccount", rs.getString(4));
            lines.add(l);
        }, id);
        Map<String, Object> p = header(switch ((String) d.get("kind")) {
            case "CREDIT_MEMO" -> "CREDIT_MEMO";
            case "DEBIT_MEMO" -> "DEBIT_MEMO";
            default -> "INVOICE";
        }, (String) d.get("document_number"), ((Date) d.get("document_date")).toLocalDate());
        p.put("dueDate", d.get("due_date").toString());
        p.put("relatedDocumentNumber", d.get("related_number"));
        p.put("buyer", party((String) d.get("c_code"), (String) d.get("c_name"), (String) d.get("c_tin"), (String) d.get("c_address")));
        p.put("lines", lines);
        p.put("vatCode", d.get("vat_code"));
        p.put("vatRate", d.get("vat_rate") == null ? null : d.get("vat_rate").toString());
        p.put("netAmount", d.get("net_amount").toString());
        p.put("vatAmount", d.get("vat_amount").toString());
        p.put("grossAmount", d.get("gross_amount").toString());
        return p;
    }

    private Map<String, Object> receipt(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.*, c.code AS c_code, c.name AS c_name, c.tin AS c_tin, c.address AS c_address FROM receipts r
                JOIN customers c ON c.id = r.customer_id WHERE r.id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Receipt");
        }
        Map<String, Object> r = rows.get(0);
        if (!"POSTED".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only posted documents are transmitted.");
        }
        List<Map<String, Object>> applied = new ArrayList<>();
        jdbc.query("SELECT d.document_number, a.amount FROM receipt_allocations a JOIN billing_documents d ON d.id = a.document_id "
                + "WHERE a.receipt_id = ? AND a.posted_entry_id IS NOT NULL ORDER BY a.created_at", rs -> {
            applied.add(Map.of("documentNumber", rs.getString(1), "amount", rs.getBigDecimal(2).toPlainString()));
        }, id);
        Map<String, Object> p = header("RECEIPT", (String) r.get("receipt_number"), ((Date) r.get("receipt_date")).toLocalDate());
        p.put("buyer", party((String) r.get("c_code"), (String) r.get("c_name"), (String) r.get("c_tin"), (String) r.get("c_address")));
        p.put("paymentMethod", r.get("payment_method"));
        p.put("paymentReference", r.get("payment_reference"));
        p.put("amount", r.get("amount").toString());
        p.put("taxWithheld", r.get("tax_withheld").toString());
        p.put("appliedTo", applied);
        return p;
    }

    private Map<String, Object> header(String type, String number, LocalDate date) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("schema", SCHEMA);
        p.put("schemaVersion", SCHEMA_VERSION);
        p.put("notice", "CAS internal canonical format; not the BIR EIS schema (C-06). Numbers are the interim CAS series (C-29).");
        p.put("documentType", type);
        p.put("documentNumber", number);
        p.put("documentDate", date.toString());
        p.put("currency", "PHP");
        p.put("seller", party(null, sellerName, sellerTin.isBlank() ? null : sellerTin, null));
        return p;
    }

    private static Map<String, Object> party(String code, String name, String tin, String address) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (code != null) {
            m.put("code", code);
        }
        m.put("name", name);
        m.put("tin", tin);
        m.put("address", address);
        return m;
    }
}
