package com.miaa.cas.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.miaa.cas.identity.SodPolicy.Rule;
import com.miaa.cas.identity.SodPolicy.Severity;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SodPolicyTest {

    private final List<Rule> rules = List.of(
            new Rule("USER_ADMINISTER", "JOURNAL_APPROVE", Severity.BLOCK, "admin vs finance"),
            new Rule("JOURNAL_CREATE", "JOURNAL_POST", Severity.BLOCK, "maker vs poster"),
            new Rule("JOURNAL_CREATE", "JOURNAL_APPROVE", Severity.WARN, "maker vs approver"));

    @Test
    void noConflictForSeparatedDuties() {
        assertThat(SodPolicy.evaluate(Set.of("JOURNAL_CREATE", "JOURNAL_SUBMIT", "LEDGER_VIEW"), rules)).isEmpty();
    }

    @Test
    void detectsBlockingAndWarningConflicts() {
        var conflicts = SodPolicy.evaluate(Set.of("JOURNAL_CREATE", "JOURNAL_POST", "JOURNAL_APPROVE"), rules);
        assertThat(conflicts).hasSize(2);
        assertThat(SodPolicy.blocking(conflicts)).singleElement()
                .satisfies(c -> assertThat(c.permissionB()).isEqualTo("JOURNAL_POST"));
    }

    @Test
    void administratorCannotHoldFinancialApproval() {
        assertThat(SodPolicy.blocking(SodPolicy.evaluate(Set.of("USER_ADMINISTER", "JOURNAL_APPROVE"), rules))).hasSize(1);
    }
}
