package com.miaa.cas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Accounting engine settings ({@code cas.accounting.*}).
 *
 * @param allowPlaceholderAccounts whether vouchers may use accounts imported as placeholders. Must be
 *        false in production so nothing posts to the non-approved placeholder chart.
 * @param recurringEnabled whether the daily recurring-journal generator runs on this instance
 * @param recurringCron schedule of the generator (Asia/Manila)
 */
@ConfigurationProperties(prefix = "cas.accounting")
public record AccountingProperties(boolean allowPlaceholderAccounts, boolean recurringEnabled, String recurringCron) {
}
