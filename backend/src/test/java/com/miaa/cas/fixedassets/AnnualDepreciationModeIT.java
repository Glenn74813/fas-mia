package com.miaa.cas.fixedassets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Annual depreciation (C-30 option): a run is accepted only in the fiscal year's last regular period. */
@TestPropertySource(properties = "cas.fixed-assets.depreciation-frequency=ANNUAL")
class AnnualDepreciationModeIT extends AbstractAccountingIT {

    @Test
    void midYearRunsAreRefusedInAnnualMode() throws Exception {
        int year = newOpenYear();
        assertThat(body(mvc.perform(get("/api/v1/depreciation-settings").header("Authorization", preparer.bearer())).andReturn())
                .get("frequency").asText()).isEqualTo("ANNUAL");
        JsonNode refused = body(mvc.perform(post("/api/v1/depreciation-runs").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("periodId", period(year, 6).getId()))))
                .andExpect(status().isUnprocessableEntity()).andReturn());
        assertThat(refused.get("detail").asText()).contains("once a year").contains(period(year, 12).getName());
    }
}
