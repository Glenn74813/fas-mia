package com.miaa.cas.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The scheduled-report e-mails: plain text, a link to My reports, and no report content. */
class ScheduledReportEmailTemplatesTest {

    @Test
    void readyAndFailedMessagesLinkToMyReports() {
        EmailTemplates.Rendered ready = EmailTemplates.render("scheduled-report-ready", Map.of("scheduleName", "Monthly collections",
                "reportType", "collection summary", "fileName", "collection_summary_2026-09.xlsx", "retentionDays", 30L), "https://cas.example");
        assertThat(ready.subject()).isEqualTo("Your scheduled report is ready: Monthly collections");
        assertThat(ready.body()).contains("collection_summary_2026-09.xlsx").contains("30 days")
                .contains("https://cas.example/reports/jobs").contains("not attached");

        EmailTemplates.Rendered failed = EmailTemplates.render("scheduled-report-failed", Map.of("scheduleName", "Monthly collections",
                "reportType", "collection summary", "error", "parameters.from is not a valid date."), "https://cas.example");
        assertThat(failed.subject()).isEqualTo("Your scheduled report failed: Monthly collections");
        assertThat(failed.body()).contains("parameters.from is not a valid date.").contains("https://cas.example/reports/jobs");
    }
}
