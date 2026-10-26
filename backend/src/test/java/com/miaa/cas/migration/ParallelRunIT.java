package com.miaa.cas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class ParallelRunIT extends AbstractAccountingIT {

    private static final String HEADER = "legacy_account_code,legacy_account_name,cas_account_code,debit,credit\n";

    @Test
    void everyDifferenceIsExplainedBeforeASecondPersonSignsOff() throws Exception {
        int year = newOpenYear();
        LocalDate date = period(year, 1).getStartDate().plusDays(9);
        String periodId = period(year, 1).getId().toString();
        postThroughWorkflow(voucher("STANDARD", date, utilitiesLines("1000.00")));
        postThroughWorkflow(voucher("STANDARD", date, List.of(line(account("PLC-5202"), costCenter("PLC-CC-OPS"), null, "50.00", "0"),
                line(account("PLC-1102"), "0", "50.00"))));

        // A mapping to an unknown CAS account rejects the whole file.
        upload(periodId, HEADER + "L1,Bank,NOPE-1,10.00,\n").andExpect(status().isBadRequest());

        // Legacy: utilities agree; the bank, split over two legacy accounts, differs; a suspense account has no CAS
        // counterpart; CAS has an expense the legacy system does not.
        String legacy = HEADER
                + "L-5201,Utilities,PLC-5201,1000.00,\n"
                + "L-1102A,Bank A,PLC-1102,,600.00\n"
                + "L-1102B,Bank B,PLC-1102,,300.00\n"
                + "L-9999,Suspense,,,100.00\n";
        JsonNode run = body(upload(periodId, legacy).andExpect(status().isCreated()).andReturn());
        String id = run.at("/summary/id").asText();
        assertThat(run.at("/summary/lineCounts/MATCHED").asInt()).isEqualTo(1);
        assertThat(run.at("/summary/lineCounts/DIFFERENT").asInt()).isEqualTo(1);
        assertThat(run.at("/summary/lineCounts/CAS_ONLY").asInt()).isEqualTo(1);
        assertThat(run.at("/summary/lineCounts/UNMAPPED").asInt()).isEqualTo(1);
        assertThat(run.at("/summary/unexplained").asInt()).isEqualTo(3);
        assertThat(run.at("/summary/legacyTotal").asText()).isEqualTo("0.00");
        JsonNode bank = lineFor(run, "PLC-1102");
        assertThat(bank.get("legacyCodes").asText()).isEqualTo("L-1102A, L-1102B");
        assertThat(bank.get("legacyAmount").asText()).isEqualTo("-900.00");
        assertThat(bank.get("casAmount").asText()).isEqualTo("-1050.00");
        assertThat(bank.get("difference").asText()).isEqualTo("150.00");
        assertThat(lineFor(run, "PLC-5202").get("lineType").asText()).isEqualTo("CAS_ONLY");

        // Nothing can be signed off while a difference is unexplained.
        signOff(approver, id, run.at("/summary/version").asLong()).andExpect(status().isUnprocessableEntity());
        for (JsonNode l : run.get("lines")) {
            if (!"MATCHED".equals(l.get("lineType").asText())) {
                run = explain(id, l.get("id").asText(), "Timing: legacy cut-off differs from CAS for " + l.get("lineType").asText());
            }
        }
        assertThat(run.at("/summary/unexplained").asInt()).isZero();
        assertThat(run.get("availableActions").toString()).doesNotContain("SIGN_OFF");

        // A correction posted in CAS: recompare keeps only explanations whose difference is unchanged.
        postThroughWorkflow(voucher("STANDARD", date, List.of(line(account("PLC-5202"), costCenter("PLC-CC-OPS"), null, "10.00", "0"),
                line(account("PLC-1102"), "0", "10.00"))));
        run = ok(mvc.perform(post("/api/v1/parallel-runs/" + id + "/recompare").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", run.at("/summary/version").asLong())))));
        assertThat(lineFor(run, "PLC-1102").get("difference").asText()).isEqualTo("160.00");
        assertThat(run.at("/summary/unexplained").asInt()).isEqualTo(2);
        assertThat(unmapped(run).get("explanation").isNull()).isFalse();
        for (JsonNode l : run.get("lines")) {
            if (!"MATCHED".equals(l.get("lineType").asText()) && l.get("explanation").isNull()) {
                run = explain(id, l.get("id").asText(), "Correction posted in CAS after the legacy close");
            }
        }

        // The uploader can never be the signer, even through the database.
        assertThatThrownBy(() -> jdbc.update("UPDATE parallel_runs SET status = 'SIGNED_OFF', signed_off_by_id = uploaded_by_id, "
                + "signed_off_at = now() WHERE id = ?::uuid", id)).hasMessageContaining("ck_pr_two_person");
        run = ok(signOff(approver, id, run.at("/summary/version").asLong()));
        assertThat(run.at("/summary/status").asText()).isEqualTo("SIGNED_OFF");
        assertThat(run.at("/summary/signedOffBy").asText()).isNotEqualTo(run.at("/summary/uploadedBy").asText());
        mvc.perform(post("/api/v1/parallel-runs/" + id + "/lines/" + lineFor(run, "PLC-1102").get("id").asText() + "/explanation")
                .header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("explanation", "Changed after sign-off")))).andExpect(status().isConflict());

        mvc.perform(get("/api/v1/parallel-runs").header("Authorization", reviewer.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/parallel-runs/template").header("Authorization", preparer.bearer())).andExpect(status().isOk());
    }

    @Test
    void theUnclosedResultOfPriorYearsIsACasOnlyLineOnTheClosingBasis() throws Exception {
        int earlier = newOpenYear();
        postThroughWorkflow(voucher("STANDARD", period(earlier, 1).getStartDate().plusDays(3), utilitiesLines("400.00")));
        int year = newOpenYear();
        JsonNode run = body(upload(period(year, 1).getId().toString(), HEADER + "L-5201,Utilities,PLC-5201,10.00,\n", "CLOSING")
                .andExpect(status().isCreated()).andReturn());
        JsonNode unclosed = lineFor(run, "UNCLOSED");
        assertThat(unclosed.get("lineType").asText()).isEqualTo("CAS_ONLY");
        assertThat(unclosed.get("accountName").asText()).contains("year-end closing not posted");
        assertThat(run.at("/summary/unexplained").asInt()).isGreaterThanOrEqualTo(1);
    }

    private ResultActions upload(String periodId, String csv) throws Exception {
        return upload(periodId, csv, "PERIOD");
    }

    private ResultActions upload(String periodId, String csv, String basis) throws Exception {
        return mvc.perform(multipart("/api/v1/parallel-runs").file(new MockMultipartFile("file", "legacy-" + UUID.randomUUID() + ".csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8))).param("periodId", periodId).param("basis", basis)
                .param("legacySystem", "Legacy FMIS").header("Authorization", preparer.bearer()));
    }

    private JsonNode explain(String id, String lineId, String text) throws Exception {
        return ok(mvc.perform(post("/api/v1/parallel-runs/" + id + "/lines/" + lineId + "/explanation").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("explanation", text)))));
    }

    private ResultActions signOff(Session who, String id, long version) throws Exception {
        return mvc.perform(post("/api/v1/parallel-runs/" + id + "/sign-off").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", version, "note", "Reviewed with the legacy team"))));
    }

    private static JsonNode lineFor(JsonNode run, String code) {
        for (JsonNode l : run.get("lines")) {
            if (code.equals(l.get("accountCode").asText())) {
                return l;
            }
        }
        throw new AssertionError("No line for " + code);
    }

    private static JsonNode unmapped(JsonNode run) {
        for (JsonNode l : run.get("lines")) {
            if ("UNMAPPED".equals(l.get("lineType").asText())) {
                return l;
            }
        }
        throw new AssertionError("No unmapped line");
    }
}
