package com.miaa.cas.notifications;

import java.util.Map;

/**
 * Plain-text e-mails for every outbox template. Plain text on purpose: nothing in a message can run or load remote
 * content, and values from users cannot inject markup. Links are absolute, built on the application's base URL.
 */
public final class EmailTemplates {

    public record Rendered(String subject, String body) {
    }

    private static final String FOOTER = "\n\n--\nThis is an automated message from the MIAA Computerized Accounting System. Do not reply to it.";

    private EmailTemplates() {
    }

    /** @throws IllegalArgumentException for an unknown template, so the delivery attempt fails visibly instead of sending nothing useful */
    public static Rendered render(String template, Map<String, Object> m, String baseUrl) {
        return switch (template) {
            case "PASSWORD_RESET" -> new Rendered("Reset your MIAA CAS password",
                    "Hello " + v(m, "displayName") + ",\n\nA password reset was requested for your MIAA CAS account. To choose a new "
                            + "password, open this link within " + v(m, "expiresInMinutes") + " minutes:\n\n" + v(m, "resetUrl")
                            + "\n\nIf you did not ask for this, ignore this message: your password stays the same." + FOOTER);
            case "portal-access-granted" -> new Rendered("Your MIAA concessionaire portal access",
                    "Your portal account " + v(m, "username") + " is now linked to " + v(m, "customerName") + ".\n\nSign in at "
                            + baseUrl + "/portal to see your billings, payments and statement of account." + FOOTER);
            case "portal-inquiry-answered" -> new Rendered("MIAA Finance answered your inquiry " + v(m, "reference"),
                    "MIAA Finance has answered your inquiry " + v(m, "reference") + " (" + v(m, "subject") + ").\n\nRead the answer at "
                            + baseUrl + "/portal under Inquiries." + FOOTER);
            case "ticket-sla-escalation" -> new Rendered("[" + v(m, "priority") + "] Ticket " + v(m, "ticketNumber") + " escalated: " + v(m, "kind"),
                    "Ticket " + v(m, "ticketNumber") + " (" + v(m, "title") + ") has been escalated.\n\n" + v(m, "detail") + "\n\n"
                            + baseUrl + "/tickets" + FOOTER);
            case "mfa-email-code" -> new Rendered("Your MIAA CAS sign-in code: " + v(m, "code"),
                    "Your MIAA CAS sign-in code is\n\n    " + v(m, "code") + "\n\nIt works once, for the next " + v(m, "minutes")
                            + " minutes. Do not share it: MIAA staff will never ask for it.\n\nIf you did not just try to sign in, someone "
                            + "knows your password: change it now and tell your system administrator." + FOOTER);
            case "scheduled-report-ready" -> new Rendered("Your scheduled report is ready: " + v(m, "scheduleName"),
                    "Your scheduled report \"" + v(m, "scheduleName") + "\" (" + v(m, "reportType") + ") has run. The file "
                            + v(m, "fileName") + " can be downloaded for " + v(m, "retentionDays") + " days.\n\nSign in and open My reports: "
                            + baseUrl + "/reports/jobs\n\nFor security, the report itself is not attached." + FOOTER);
            case "scheduled-report-failed" -> new Rendered("Your scheduled report failed: " + v(m, "scheduleName"),
                    "Your scheduled report \"" + v(m, "scheduleName") + "\" (" + v(m, "reportType") + ") could not be generated:\n\n"
                            + v(m, "error") + "\n\nDetails are in My reports: " + baseUrl + "/reports/jobs" + FOOTER);
            case AlertService.EMAIL_TEMPLATE -> new Rendered("[MIAA CAS alert] " + v(m, "title"),
                    v(m, "body") + "\n\n" + baseUrl + v(m, "link") + FOOTER);
            default -> throw new IllegalArgumentException("No e-mail template " + template);
        };
    }

    private static String v(Map<String, Object> m, String key) {
        Object value = m.get(key);
        return value == null ? "" : value.toString();
    }
}
