package com.miaa.cas.audit.anomaly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.support.AbstractPayablesIT;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Suspicious-activity detection (TOR §2.2.3): each rule on a record built to trip it, deduplication, alerts, review. */
class AnomalyDetectionIT extends AbstractPayablesIT {

    @Autowired AnomalyDetectionService detection;

    @Test
    void duplicateInvoicesAndPaymentsAfterABankChangeAreHighSeverity() throws Exception {
        int year = newOpenYear();
        String supplier = createSupplier(false).get("id").asText();
        postedInvoice(supplier, "DUP-A-" + year, LocalDate.of(year, 2, 1), "7300.00");
        String later = postedInvoice(supplier, "DUP-B-" + year, LocalDate.of(year, 2, 11), "7300.00").get("id").asText();

        // A supplier bank account entered today, and a payment to that supplier today.
        UUID supplierId = UUID.fromString(supplier);
        jdbc.update("""
                INSERT INTO supplier_bank_accounts (id, supplier_id, bank_name, account_name, account_number_enc, account_number_last4, status,
                    entered_by_id, entered_by, entered_at, verified_by, verified_at)
                    VALUES (?, ?, 'Test Bank', 'Payee', ?, '9876', 'VERIFIED', ?, 'someone', ?, 'verifier', now())""",
                UUID.randomUUID(), supplierId, new byte[] {1}, preparerUser.id(), Timestamp.from(Instant.now()));
        UUID dv = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO disbursement_vouchers (id, dv_number, supplier_id, particulars, status, amount, payment_mode, payment_reference,
                    payment_date, prepared_by_id, prepared_by, created_at, updated_at, updated_by)
                VALUES (?, ?, ?, 'Payment', 'PAID', 7227.00, 'ADA', 'ADA-1', ?, ?, 'dvprep', now(), now(), 'dvprep')""",
                dv, "DV-T-" + dv.toString().substring(0, 8), supplierId, Date.valueOf(LocalDate.now(ZoneId.of("Asia/Manila"))), preparerUser.id());

        TestUser auditorUser = createUser("anaud", "INTERNAL_AUDITOR");
        Session auditor = login(auditorUser);
        jdbc.update("DELETE FROM alert_throttle WHERE alert_key = 'anomaly:new'");
        AnomalyDetectionService.RunResult first = detection.run();
        assertThat(first.high()).isGreaterThanOrEqualTo(2);
        Map<String, Object> dup = finding("DUPLICATE_INVOICE", later);
        assertThat(dup.get("severity")).isEqualTo("HIGH");
        assertThat((String) dup.get("detail")).contains("DUP-A-" + year).contains("7,227.00").contains("10 day(s) apart");
        assertThat((String) finding("BANK_CHANGE_PAYMENT", dv.toString()).get("detail")).contains("****9876").contains("0 day(s) after");

        // Reported once: a second run adds nothing for these documents.
        detection.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly_findings WHERE document_id IN (?::uuid, ?)", Long.class, later, dv))
                .isEqualTo(2);
        // The reviewers are alerted in the app.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND link = '/audit/anomalies'", Long.class,
                auditorUser.id()))
                .isPositive();
        assertThat(body(mvc.perform(get("/api/v1/anomalies/summary").header("Authorization", auditor.bearer())).andReturn())
                .get("openHigh").asLong()).isPositive();
    }

    @Test
    void aBackdatedRoundManualEntryPostedOnAHolidayTripsThreeRules() throws Exception {
        int year = pastOpenYear();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Manila"));
        boolean holidayAdded = jdbc.update("INSERT INTO business_holidays (holiday_date, name, created_by, created_at) "
                + "VALUES (?, 'Test holiday', 'test', now()) ON CONFLICT DO NOTHING", Date.valueOf(today)) == 1;
        try {
            JsonNode posted = postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 3, 15), utilitiesLines("1000000.00")));
            String entry = posted.get("postedEntryId").asText();
            detection.run();
            assertThat(finding("BACKDATED_ENTRY", entry).get("severity")).isEqualTo("MEDIUM");
            assertThat((String) finding("ROUND_AMOUNT", entry).get("detail")).contains("1,000,000.00");
            assertThat((String) finding("OFF_HOURS_POSTING", entry).get("detail")).contains("Test holiday");
        } finally {
            if (holidayAdded) {
                jdbc.update("DELETE FROM business_holidays WHERE holiday_date = ?", Date.valueOf(today));
            }
        }
    }

    @Test
    void findingsAreReviewedWithANoteBySomeoneNotInvolved() throws Exception {
        int year = newOpenYear();
        String supplier = createSupplier(false).get("id").asText();
        postedInvoice(supplier, "RV-A-" + year, LocalDate.of(year, 5, 1), "4100.00");
        String later = postedInvoice(supplier, "RV-B-" + year, LocalDate.of(year, 5, 3), "4100.00").get("id").asText();
        detection.run();
        Map<String, Object> f = finding("DUPLICATE_INVOICE", later);
        String id = f.get("id").toString();

        // Viewers and reviewers: an accountant has neither; an external auditor may only look.
        mvc.perform(get("/api/v1/anomalies").header("Authorization", preparer.bearer())).andExpect(status().isForbidden());
        Session external = login(createUser("anext", "EXTERNAL_AUDITOR"));
        mvc.perform(get("/api/v1/anomalies?severity=high&status=open").header("Authorization", external.bearer())).andExpect(status().isOk());
        review(external, id, 0, "EXPLAINED", "Checked the two invoices").andExpect(status().isForbidden());

        // The preparer of the invoices cannot review them, even holding the permission.
        TestUser selfUser = createUser("anself", "INTERNAL_AUDITOR");
        jdbc.update("UPDATE anomaly_findings SET involved_users = ARRAY[?] WHERE id = ?::uuid", selfUser.username(), id);
        Session self = login(selfUser);
        JsonNode refused = body(review(self, id, 0, "EXPLAINED", "Checked the two invoices").andExpect(status().isForbidden()).andReturn());
        assertThat(refused.toString()).contains("SEGREGATION_OF_DUTIES");

        Session auditor = login(createUser("anrev", "INTERNAL_AUDITOR"));
        review(auditor, id, 0, "EXPLAINED", "short").andExpect(status().isBadRequest());
        JsonNode reviewed = body(review(auditor, id, 0, "ESCALATED", "Same amount twice; asked AP for both originals")
                .andExpect(status().isOk()).andReturn());
        assertThat(reviewed.get("status").asText()).isEqualTo("ESCALATED");
        assertThat(reviewed.get("reviewedBy").asText()).startsWith("anrev");
        review(auditor, id, 1, "EXPLAINED", "Changing my mind afterwards").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'ANOMALY_REVIEWED' AND resource_id = ?",
                Long.class, id)).isEqualTo(1);
    }

    @Test
    void benfordMeasuresTheFirstDigitDeviation() {
        // Counts in Benford's own proportions conform; the same total all starting with 5 does not.
        long[] conforming = new long[10];
        long[] skewed = new long[10];
        for (int d = 1; d <= 9; d++) {
            conforming[d] = Math.round(1000 * AnomalyDetectionService.Benford.expected(d));
        }
        skewed[5] = 1000;
        assertThat(AnomalyDetectionService.Benford.of(conforming).mad()).isLessThan(0.001);
        AnomalyDetectionService.Benford b = AnomalyDetectionService.Benford.of(skewed);
        assertThat(b.mad()).isGreaterThan(AnomalyDetectionService.BENFORD_MAD_LIMIT);
        assertThat(b.worstDigit()).isEqualTo(5);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> finding(String rule, String documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM anomaly_findings WHERE rule_code = ? AND document_id = ?::uuid",
                rule, documentId);
        assertThat(rows).as(rule + " for " + documentId).hasSize(1);
        return rows.get(0);
    }

    private ResultActions review(Session who, String id, long version, String status, String note) throws Exception {
        return mvc.perform(post("/api/v1/anomalies/" + id + "/review").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", version, "status", status, "note", note))));
    }

    /** A fiscal year before this one with March open, so an entry dated in it is posted long afterwards. */
    private int pastOpenYear() {
        for (int year = 2000; year < LocalDate.now().getYear() - 1; year++) {
            int y = year;
            Boolean usable = tx.execute(s -> {
                if (jdbc.queryForObject("SELECT count(*) FROM fiscal_years WHERE fiscal_year = ?", Integer.class, y) == 0) {
                    var fy = calendar.create(y, Actor.SYSTEM);
                    for (var p : periods.findByFiscalYearIdOrderByPeriodNumberAsc(fy.id())) {
                        calendar.openPeriod(p.getId(), p.getVersion(), Actor.SYSTEM);
                    }
                    return true;
                }
                return jdbc.queryForObject("SELECT count(*) FROM accounting_periods WHERE sequence_key = ? AND status = 'OPEN'",
                        Integer.class, y * 100 + 3) == 1;
            });
            if (Boolean.TRUE.equals(usable)) {
                return y;
            }
        }
        throw new IllegalStateException("No past fiscal year with March open");
    }
}
