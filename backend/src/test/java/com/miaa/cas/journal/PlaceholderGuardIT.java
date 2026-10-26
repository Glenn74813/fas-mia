package com.miaa.cas.journal;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With production settings, nothing may be submitted or posted against the placeholder chart. */
@TestPropertySource(properties = "cas.accounting.allow-placeholder-accounts=false")
class PlaceholderGuardIT extends AbstractAccountingIT {

    @Test
    void placeholderAccountsAreRejectedWhenNotAllowed() throws Exception {
        int year = newOpenYear();
        JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 2, 2), utilitiesLines("1.00")));
        attachPdf(preparer, v.get("id").asText());
        action(preparer, v.get("id").asText(), "submit", v.get("version").asLong(), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[?(@ =~ /.*placeholder chart.*/)]").exists());
    }
}
