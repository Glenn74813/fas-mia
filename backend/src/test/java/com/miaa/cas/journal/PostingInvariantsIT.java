package com.miaa.cas.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;

class PostingInvariantsIT extends AbstractAccountingIT {

    @Test
    void unbalancedVoucherCannotBeSubmitted() throws Exception {
        int year = newOpenYear();
        JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 1, 10), List.of(
                line(account("PLC-5201"), costCenter("PLC-CC-FIN"), null, "100.00", "0"),
                line(account("PLC-1102"), "0", "99.99"))));
        assertThat(v.get("balanced").asBoolean()).isFalse();
        attachPdf(preparer, v.get("id").asText());
        action(preparer, v.get("id").asText(), "submit", v.get("version").asLong(), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0]").value("Debits (100.00) do not equal credits (99.99)."));
    }

    @Test
    void accountAndDimensionRulesAreEnforced() throws Exception {
        int year = newOpenYear();
        JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 1, 10), List.of(
                line(account("PLC-5201"), "100.00", "0"),               // requires a cost centre
                line(account("PLC-1201"), "0", "60.00"),                // requires a customer
                line(account("PLC-11"), "0", "40.00"))));               // header account
        attachPdf(preparer, v.get("id").asText());
        action(preparer, v.get("id").asText(), "submit", v.get("version").asLong(), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[?(@ =~ /.*requires a cost centre.*/)]").exists())
                .andExpect(jsonPath("$.violations[?(@ =~ /.*requires a customer subsidiary account.*/)]").exists())
                .andExpect(jsonPath("$.violations[?(@ =~ /.*header account.*/)]").exists());
    }

    @Test
    void invalidAmountsAreRejectedAtTheApi() throws Exception {
        int year = newOpenYear();
        Map<String, Object> body = voucher("STANDARD", LocalDate.of(year, 1, 10), List.of(
                line(account("PLC-5201"), costCenter("PLC-CC-FIN"), null, "100.005", "0"),
                line(account("PLC-1102"), "0", "-100.00")));
        mvc.perform(post("/api/v1/journal-vouchers").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void postedEntriesAreImmutableAndBalancedAtTheDatabase() throws Exception {
        int year = newOpenYear();
        JsonNode posted = postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 6, 30), utilitiesLines("999.99")));
        UUID entryId = UUID.fromString(posted.get("postedEntryId").asText());
        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entry_lines SET debit = debit + 1 WHERE entry_id = ?", entryId))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_entries WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");

        // Even a direct insert bypassing the service cannot commit an unbalanced entry.
        UUID periodId = period(year, 6).getId();
        UUID fyId = period(year, 6).getFiscalYearId();
        UUID voucherId = UUID.fromString(posted.get("id").asText());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID rogue = UUID.randomUUID();
            jdbc.update("INSERT INTO journal_vouchers (id, voucher_type, status, accounting_date, period_id, description, "
                    + "prepared_by_id, prepared_by, created_at, updated_at, updated_by) SELECT ?, 'STANDARD', 'POSTED', "
                    + "accounting_date, period_id, 'rogue', prepared_by_id, prepared_by, now(), now(), 'x' FROM journal_vouchers "
                    + "WHERE id = ?", rogue, voucherId);
            jdbc.update("INSERT INTO journal_entries (id, entry_number, voucher_id, voucher_type, fiscal_year_id, period_id, "
                    + "accounting_date, description, source_type, currency_code, total_debit, total_credit, prepared_by, "
                    + "posted_by_id, posted_by, posted_at) VALUES (?, 'ROGUE-1', ?, 'STANDARD', ?, ?, ?, 'rogue', 'MANUAL', "
                    + "'PHP', 10, 10, 'x', ?, 'x', now())", rogue, rogue, fyId, periodId, LocalDate.of(year, 6, 30),
                    preparerUser.id());
            jdbc.update("INSERT INTO journal_entry_lines (id, entry_id, line_no, account_id, debit, credit, period_id, "
                    + "accounting_date) VALUES (?, ?, 1, ?, 10, 0, ?, ?)", UUID.randomUUID(), rogue, account("PLC-1102"),
                    periodId, LocalDate.of(year, 6, 30));
        })).hasStackTraceContaining("not balanced");
    }

    @Test
    void trialBalanceAndCacheAgreeAfterPostings() throws Exception {
        int year = newOpenYear();
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 1, 15), utilitiesLines("1000.00")));
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 2, 15), utilitiesLines("250.25")));
        UUID feb = period(year, 2).getId();
        JsonNode tb = body(mvc.perform(get("/api/v1/general-ledger/trial-balance").param("periodId", feb.toString())
                .header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(tb.get("balanced").asBoolean()).isTrue();
        assertThat(tb.get("placeholderAccountsUsed").asBoolean()).isTrue();
        JsonNode utilities = find(tb.get("rows"), "PLC-5201");
        assertThat(utilities.get("openingDebit").asText()).isEqualTo("1000.00");
        assertThat(utilities.get("periodDebit").asText()).isEqualTo("250.25");
        assertThat(utilities.get("closingDebit").asText()).isEqualTo("1250.25");
        mvc.perform(get("/api/v1/general-ledger/balance-differences").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        JsonNode ledger = body(mvc.perform(get("/api/v1/general-ledger/accounts/" + account("PLC-1102") + "/ledger")
                .param("from", year + "-02-01").param("to", year + "-02-28").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
        // Cash is a debit-normal balance-sheet account (its opening carries earlier years); February's
        // credit of 250.25 lowers it, and the running balance of the only line equals the closing balance.
        java.math.BigDecimal opening = new java.math.BigDecimal(ledger.get("openingBalance").asText());
        java.math.BigDecimal closing = new java.math.BigDecimal(ledger.get("closingBalance").asText());
        assertThat(closing.subtract(opening)).isEqualByComparingTo("-250.25");
        assertThat(ledger.get("periodCredit").asText()).isEqualTo("250.25");
        assertThat(ledger.at("/lines/0/runningBalance").asText()).isEqualTo(ledger.get("closingBalance").asText());

        // Utilities is a nominal account: its ledger opening only counts the current fiscal year.
        JsonNode expense = body(mvc.perform(get("/api/v1/general-ledger/accounts/" + account("PLC-5201") + "/ledger")
                .param("from", year + "-02-01").param("to", year + "-02-28").header("Authorization", approver.bearer()))
                .andReturn());
        assertThat(expense.get("openingBalance").asText()).isEqualTo("1000.00");
        assertThat(expense.get("closingBalance").asText()).isEqualTo("1250.25");
    }

    @Test
    void corruptedBalanceCacheIsDetectedAndRebuiltFromEntries() throws Exception {
        int year = newOpenYear();
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 8, 8), utilitiesLines("77.00")));
        UUID aug = period(year, 8).getId();
        jdbc.update("UPDATE ledger_balances SET debit_total = debit_total + 5 WHERE period_id = ? AND account_id = ?",
                aug, account("PLC-5201"));
        mvc.perform(get("/api/v1/general-ledger/balance-differences").param("periodId", aug.toString())
                .header("Authorization", approver.bearer())).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/v1/general-ledger/balances/rebuild").header("Authorization", preparer.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/general-ledger/balances/rebuild").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.differencesFound").value(1));
        mvc.perform(get("/api/v1/general-ledger/balance-differences").header("Authorization", approver.bearer()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void concurrentPostingProducesExactlyOneEntryAndGapFreeNumbers() throws Exception {
        int year = newOpenYear();
        List<String> ids = new ArrayList<>();
        List<Long> versions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 7, 1 + i), utilitiesLines("10.00")));
            String id = v.get("id").asText();
            attachPdf(preparer, id);
            v = ok(action(preparer, id, "submit", v.get("version").asLong(), null));
            v = ok(action(reviewer, id, "approve", v.get("version").asLong(), null));
            v = ok(action(approver, id, "approve", v.get("version").asLong(), null));
            ids.add(id);
            versions.add(v.get("version").asLong());
        }
        // Two posters race on the first voucher with different keys.
        Session secondPoster = login(createUser("post2", "CHIEF_ACCOUNTANT"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (Session s : List.of(poster, secondPoster)) {
            Callable<Integer> c = () -> postVoucher(s, ids.get(0), versions.get(0), "race-" + UUID.randomUUID())
                    .andReturn().getResponse().getStatus();
            results.add(pool.submit(c));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        ok(postVoucher(poster, ids.get(1), versions.get(1), "seq-" + UUID.randomUUID()));
        ok(postVoucher(poster, ids.get(2), versions.get(2), "seq-" + UUID.randomUUID()));
        List<String> numbers = jdbc.queryForList("SELECT e.entry_number FROM journal_entries e JOIN accounting_periods p "
                + "ON p.id = e.period_id WHERE p.sequence_key / 100 = ? ORDER BY e.entry_number", String.class, year);
        assertThat(numbers).containsExactly("JEV-" + year + "-000001", "JEV-" + year + "-000002", "JEV-" + year + "-000003");
    }

    @Test
    void reversalMirrorsTheEntryAndCanOnlyHappenOnce() throws Exception {
        int year = newOpenYear();
        JsonNode posted = postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 3, 1), utilitiesLines("300.00")));
        String entryId = posted.get("postedEntryId").asText();
        Map<String, Object> req = Map.of("accountingDate", year + "-03-05", "adjustmentPeriod", false,
                "reason", "Posted to the wrong cost centre");
        mvc.perform(post("/api/v1/journal-entries/" + entryId + "/reversal").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(req))).andExpect(status().isForbidden());
        JsonNode rev = body(mvc.perform(post("/api/v1/journal-entries/" + entryId + "/reversal")
                .header("Authorization", approver.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(req)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(rev.get("voucherType").asText()).isEqualTo("REVERSAL");
        assertThat(rev.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(rev.at("/lines/0/credit").asText()).isEqualTo("300.00");
        mvc.perform(post("/api/v1/journal-entries/" + entryId + "/reversal").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(req))).andExpect(status().isConflict());

        String rid = rev.get("id").asText();
        rev = ok(action(reviewer, rid, "approve", rev.get("version").asLong(), null));
        // The approver initiated the reversal, so a different approver must approve it.
        action(approver, rid, "approve", rev.get("version").asLong(), null).andExpect(status().isForbidden());
        rev = ok(action(poster, rid, "approve", rev.get("version").asLong(), null));
        Session thirdChief = login(createUser("chief3", "CHIEF_ACCOUNTANT"));
        ok(postVoucher(thirdChief, rid, rev.get("version").asLong(), "rev-" + UUID.randomUUID()));

        mvc.perform(get("/api/v1/general-ledger/entries/" + entryId).header("Authorization", approver.bearer()))
                .andExpect(jsonPath("$.reversedByEntryNumber").value("JEV-" + year + "-000002"))
                .andExpect(jsonPath("$.totalDebit").value("300.00"));
        JsonNode tb = body(mvc.perform(get("/api/v1/general-ledger/trial-balance").param("periodId",
                period(year, 3).getId().toString()).header("Authorization", approver.bearer())).andReturn());
        JsonNode utilities = find(tb.get("rows"), "PLC-5201");
        assertThat(utilities.get("closingDebit").asText()).isEqualTo("0.00");
        assertThat(utilities.get("closingCredit").asText()).isEqualTo("0.00");
    }

    private static JsonNode find(JsonNode rows, String code) {
        for (JsonNode r : rows) {
            if (code.equals(r.get("code").asText())) {
                return r;
            }
        }
        throw new AssertionError("Row " + code + " not in trial balance");
    }
}
