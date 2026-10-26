package com.miaa.cas.identity;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Pure segregation-of-duties evaluation: which configured conflict rules a permission set
 * violates. Persistence and enforcement decisions live in the calling services.
 */
public final class SodPolicy {

    public enum Severity { BLOCK, WARN }

    public record Rule(String permissionA, String permissionB, Severity severity, String rationale) {
    }

    public record Conflict(String permissionA, String permissionB, Severity severity, String rationale) {
    }

    private SodPolicy() {
    }

    public static List<Conflict> evaluate(Set<String> permissions, Collection<Rule> rules) {
        return rules.stream()
                .filter(r -> permissions.contains(r.permissionA()) && permissions.contains(r.permissionB()))
                .map(r -> new Conflict(r.permissionA(), r.permissionB(), r.severity(), r.rationale()))
                .toList();
    }

    public static List<Conflict> blocking(List<Conflict> conflicts) {
        return conflicts.stream().filter(c -> c.severity() == Severity.BLOCK).toList();
    }
}
