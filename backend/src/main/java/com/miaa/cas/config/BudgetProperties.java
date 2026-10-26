package com.miaa.cas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budget control policy ({@code cas.budget.enforcement}): BLOCK refuses expenditure beyond the
 * available allotment, WARN allows it and records an audit warning, OFF disables the check. MIAA
 * policy decides the production value (clarification C-10); the default is BLOCK.
 */
@ConfigurationProperties(prefix = "cas.budget")
public record BudgetProperties(String enforcement) {

    public boolean blocks() {
        return enforcement == null || "BLOCK".equalsIgnoreCase(enforcement);
    }

    public boolean off() {
        return "OFF".equalsIgnoreCase(enforcement);
    }
}
