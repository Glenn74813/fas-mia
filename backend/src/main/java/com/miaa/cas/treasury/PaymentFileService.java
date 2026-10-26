package com.miaa.cas.treasury;

import com.miaa.cas.accountspayable.ApDtos.PaymentInstructionRequest;
import com.miaa.cas.accountspayable.DisbursementService;
import com.miaa.cas.accountspayable.SupplierBankService;
import com.miaa.cas.accountspayable.SupplierBankService.PayeeAccount;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.crypto.ContentCipher;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.journal.NumberingService;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADA payment files. The cashier selects approved disbursement vouchers whose suppliers have a verified
 * bank account; CAS issues each payment instruction (mode ADA, reference = batch number and line) and
 * produces one file for upload to the bank. The layout is the CAS generic CSV, not a bank format: the
 * participating banks' layouts (or LDDAP-ADA) are mapped once confirmed (C-11). The file holds full
 * account numbers, so it is kept encrypted at rest only by the database and every download is audited.
 */
@Service
public class PaymentFileService {

    public record EligibleDv(UUID dvId, String dvNumber, String supplierName, BigDecimal amount, String payeeBank, String payeeAccountMasked,
            String blockedReason, long version) {
    }

    public record BatchRequest(UUID bankAccountId, LocalDate paymentDate, List<UUID> dvIds) {
    }

    public record BatchView(UUID id, String batchNumber, String bankAccountCode, LocalDate paymentDate, int itemCount, BigDecimal totalAmount,
            String fileName, String sha256, String createdBy, Instant createdAt, int downloadCount, List<Map<String, Object>> items) {
    }

    public record BatchFile(String fileName, byte[] content) {
    }

    private final DisbursementService disbursements;
    private final SupplierBankService supplierBanks;
    private final BankAccountService banks;
    private final NumberingService numbering;
    private final AuditService audit;
    private final ContentCipher cipher;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PaymentFileService(DisbursementService disbursements, SupplierBankService supplierBanks, BankAccountService banks,
            NumberingService numbering, AuditService audit, ContentCipher cipher, JdbcTemplate jdbc, Clock clock) {
        this.disbursements = disbursements;
        this.supplierBanks = supplierBanks;
        this.banks = banks;
        this.numbering = numbering;
        this.audit = audit;
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Approved vouchers awaiting payment, with the reason any of them cannot go in a file. */
    @Transactional(readOnly = true)
    public List<EligibleDv> eligible() {
        return jdbc.query("""
                SELECT v.id, v.dv_number, s.name, v.amount, v.version, b.bank_name, b.account_number_last4, b.status AS bank_status
                FROM disbursement_vouchers v JOIN suppliers s ON s.id = v.supplier_id
                LEFT JOIN supplier_bank_accounts b ON b.supplier_id = v.supplier_id AND b.status IN ('PENDING', 'VERIFIED')
                WHERE v.status = 'APPROVED' ORDER BY v.dv_number""", (rs, i) -> {
            String bankStatus = rs.getString("bank_status");
            String reason = bankStatus == null ? "Supplier has no bank account on file"
                    : "PENDING".equals(bankStatus) ? "Supplier bank account awaits verification" : null;
            return new EligibleDv(rs.getObject("id", UUID.class), rs.getString("dv_number"), rs.getString("name"), rs.getBigDecimal("amount"),
                    rs.getString("bank_name"), rs.getString("account_number_last4") == null ? null : "****" + rs.getString("account_number_last4"),
                    reason, rs.getLong("version"));
        });
    }

    @Transactional
    public BatchView create(BatchRequest req) {
        CurrentActor.requirePermission("PAYMENT_RELEASE");
        if (req.dvIds() == null || req.dvIds().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Select at least one voucher.");
        }
        var bank = banks.requireActive(req.bankAccountId());
        String number = numbering.next("PAY", req.paymentDate().getYear());
        List<String> problems = new ArrayList<>();
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("format,CAS-GENERIC-PAYMENT-FILE,version,1,notice,Not a bank-specific layout (C-11)\r\n");
        csv.append("batch_number,line,dv_number,payee_name,payee_bank,payee_branch,payee_account_name,payee_account_number,amount,currency,"
                + "payment_date,debit_account_last4,reference\r\n");
        BigDecimal total = BigDecimal.ZERO;
        int line = 0;
        List<Object[]> items = new ArrayList<>();
        for (UUID dvId : new TreeSet<>(req.dvIds())) {
            Map<String, Object> dv = jdbc.queryForMap("SELECT v.*, s.name AS supplier_name FROM disbursement_vouchers v JOIN suppliers s "
                    + "ON s.id = v.supplier_id WHERE v.id = ? FOR UPDATE OF v", dvId);
            String dvNumber = (String) dv.get("dv_number");
            if (!"APPROVED".equals(dv.get("status"))) {
                problems.add("DV " + dvNumber + " is not awaiting payment.");
                continue;
            }
            PayeeAccount payee = supplierBanks.verifiedAccount((UUID) dv.get("supplier_id"));
            if (payee == null) {
                problems.add("DV " + dvNumber + ": the supplier has no verified bank account.");
                continue;
            }
            line++;
            String reference = number + "-" + line;
            BigDecimal amount = (BigDecimal) dv.get("amount");
            // Issues the instruction through the normal DV control path (permission and segregation checks).
            disbursements.instruct(dvId, new PaymentInstructionRequest(((Number) dv.get("version")).longValue(), "ADA", bank.id(), reference,
                    req.paymentDate()));
            csv.append(String.join(",", q(number), String.valueOf(line), q(dvNumber), q((String) dv.get("supplier_name")), q(payee.bankName()),
                    q(payee.branch()), q(payee.accountName()), q(payee.accountNumber()), amount.toPlainString(), "PHP",
                    req.paymentDate().toString(), bank.accountNumberLast4(), q(reference))).append("\r\n");
            total = total.add(amount);
            items.add(new Object[] {line, dvId, payee.id(), amount});
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, problems.get(0), Map.of("violations", problems));
        }
        csv.append("total,").append(line).append(',').append(Money.of(total).toPlainString()).append("\r\n");
        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        String sha = sha256(content);
        UUID id = UUID.randomUUID();
        String fileName = number + ".csv";
        // The file carries payee bank account numbers: it is stored encrypted, bound to this batch.
        jdbc.update("INSERT INTO payment_batches (id, batch_number, bank_account_id, payment_date, item_count, total_amount, file_name, content, "
                + "content_encryption, sha256, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, number, bank.id(),
                Date.valueOf(req.paymentDate()), line, Money.of(total), fileName, cipher.seal(content, "payment-file", id.toString()),
                ContentCipher.SCHEME, sha, CurrentActor.username(), Timestamp.from(Instant.now(clock)));
        for (Object[] it : items) {
            jdbc.update("INSERT INTO payment_batch_items (batch_id, line_no, dv_id, supplier_bank_account_id, amount) VALUES (?, ?, ?, ?, ?)",
                    id, it[0], it[1], it[2], it[3]);
        }
        audit.record("PAYMENT_FILE_CREATED", "PAYMENT_BATCH", number, Map.of("bank", bank.code(), "items", line,
                "total", Money.of(total).toPlainString(), "sha256", sha));
        return view(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<BatchView> list(int page, int size) {
        return JdbcPaging.ids(jdbc, "SELECT id FROM payment_batches ORDER BY created_at DESC, id", page, size).map(this::view);
    }

    @Transactional
    public BatchFile download(UUID id) {
        CurrentActor.requirePermission("PAYMENT_RELEASE");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT batch_number, file_name, content, content_encryption, sha256 FROM payment_batches "
                + "WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Payment batch");
        }
        Map<String, Object> b = rows.get(0);
        jdbc.update("UPDATE payment_batches SET download_count = download_count + 1 WHERE id = ?", id);
        audit.record("PAYMENT_FILE_DOWNLOADED", "PAYMENT_BATCH", b.get("batch_number"), Map.of("sha256", b.get("sha256")));
        return new BatchFile((String) b.get("file_name"),
                cipher.open((byte[]) b.get("content"), (String) b.get("content_encryption"), "payment-file", id.toString()));
    }

    private BatchView view(UUID id) {
        Map<String, Object> b = jdbc.queryForMap("SELECT p.*, a.code AS bank_code FROM payment_batches p JOIN bank_accounts a ON a.id = p.bank_account_id "
                + "WHERE p.id = ?", id);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT i.line_no AS "lineNo", v.dv_number AS "dvNumber", s.name AS "payee", '****' || sb.account_number_last4 AS "account",
                       i.amount, v.status AS "dvStatus"
                FROM payment_batch_items i JOIN disbursement_vouchers v ON v.id = i.dv_id JOIN suppliers s ON s.id = v.supplier_id
                JOIN supplier_bank_accounts sb ON sb.id = i.supplier_bank_account_id WHERE i.batch_id = ? ORDER BY i.line_no""", id);
        return new BatchView(id, (String) b.get("batch_number"), (String) b.get("bank_code"), ((Date) b.get("payment_date")).toLocalDate(),
                ((Number) b.get("item_count")).intValue(), (BigDecimal) b.get("total_amount"), (String) b.get("file_name"), (String) b.get("sha256"),
                (String) b.get("created_by"), ((Timestamp) b.get("created_at")).toInstant(), ((Number) b.get("download_count")).intValue(), items);
    }

    /** CSV field, quoted, with spreadsheet formula characters neutralised. */
    static String q(String s) {
        String v = s == null ? "" : s;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
