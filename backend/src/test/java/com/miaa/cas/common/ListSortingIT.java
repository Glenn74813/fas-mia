package com.miaa.cas.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/** Sortable list endpoints: whitelisted keys in both directions, unknown keys refused, and paging stable on ties. */
class ListSortingIT extends AbstractReceivablesIT {

    @Test
    void customersAndSuppliersSortByWhitelistedKeys() throws Exception {
        String tag = "Sort" + System.nanoTime() % 1_000_000;
        createCustomerNamed(tag + " bravo");
        createCustomerNamed(tag + " Alpha");
        createCustomerNamed(tag + " charlie");

        // Name sorts without regard to case, both ways.
        assertThat(names("/api/v1/customers?q=" + tag + "&sort=name")).containsExactly(tag + " Alpha", tag + " bravo", tag + " charlie");
        assertThat(names("/api/v1/customers?q=" + tag + "&sort=-name")).containsExactly(tag + " charlie", tag + " bravo", tag + " Alpha");
        List<String> codes = field("/api/v1/customers?q=" + tag + "&sort=-code", "code");
        assertThat(codes).isSortedAccordingTo(java.util.Comparator.reverseOrder());

        mvc.perform(get("/api/v1/customers?sort=tin").header("Authorization", approver.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("Allowed: code, name, status")));
        mvc.perform(get("/api/v1/suppliers?sort=name;drop").header("Authorization", approver.bearer())).andExpect(status().isBadRequest());

        createSupplier(false);
        createSupplier(false);
        assertThat(field("/api/v1/suppliers?size=200&sort=code", "code")).isSorted();
    }

    @Test
    void usersWhoNeverSignedInComeLastEitherWay() throws Exception {
        createUser("never", "ACCOUNTANT");
        for (String sort : List.of("lastLoginAt", "-lastLoginAt")) {
            JsonNode body = body(mvc.perform(get("/api/v1/users?size=200&sort=" + sort).header("Authorization",
                    login(createUser("sortadm", "SYSTEM_ADMINISTRATOR")).bearer())).andExpect(status().isOk()).andReturn());
            List<Boolean> signedIn = new ArrayList<>();
            body.get("items").forEach(u -> signedIn.add(!u.get("lastLoginAt").isNull()));
            // Every signed-in user comes before every user who never signed in.
            assertThat(signedIn.indexOf(false) == -1 || !signedIn.subList(signedIn.indexOf(false), signedIn.size()).contains(true))
                    .as(sort).isTrue();
        }
    }

    @Test
    void pagingOnTiedValuesNeitherRepeatsNorSkipsRows() throws Exception {
        int year = newOpenYear();
        // Five vouchers on the same date: sorting by date alone would tie; the id keeps the pages consistent.
        Set<String> created = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            created.add(createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 2, 1), utilitiesLines("10.00"))).get("id").asText());
        }
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 50; page++) {
            JsonNode body = body(mvc.perform(get("/api/v1/journal-vouchers?mine=true&sort=-accountingDate&size=2&page=" + page)
                    .header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
            body.get("items").forEach(v -> seen.add(v.get("id").asText()));
            if (page + 1 >= body.get("totalPages").asInt()) {
                break;
            }
        }
        assertThat(seen).doesNotHaveDuplicates().containsAll(created);
    }

    private List<String> names(String url) throws Exception {
        return field(url, "name");
    }

    private List<String> field(String url, String name) throws Exception {
        JsonNode body = body(mvc.perform(get(url).header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn());
        List<String> out = new ArrayList<>();
        body.get("items").forEach(i -> out.add(i.get(name).asText()));
        return out;
    }
}
