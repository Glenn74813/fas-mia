package com.miaa.cas.common.logging;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets and personal data in log output (MS §9.4): tokens, passwords and one-time codes, Philippine TINs,
 * account numbers and e-mail addresses. Applied to every message and stack trace, in the plain-text console format
 * and in the structured (ECS JSON) production format. Code should still avoid logging such values; this is the
 * safety net for the cases it misses (driver errors, third-party messages).
 */
public final class LogMasker {

    private record Rule(Pattern pattern, String replacement) {
    }

    private static final List<Rule> RULES = List.of(
            // JSON Web Tokens (access tokens, ID tokens).
            new Rule(Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*"), "[JWT]"),
            // Authorization headers.
            new Rule(Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}"), "$1 [REDACTED]"),
            // name=value or "name": "value" pairs whose value is a secret.
            new Rule(Pattern.compile("(?i)(\"?\\b(?:password|passwd|pwd|new_?password|current_?password|temporary_?password|secret|client_?secret"
                    + "|token|access_?token|refresh_?token|reset_?token|mfa_?token|id_?token|api_?key|apikey|otp|totp|mfa_?code"
                    + "|verification_?code|authorization|cookie|set-cookie)\"?\\s*[:=]\\s*\"?)([^\"\\s,;&}]+)"), "$1[REDACTED]"),
            // Philippine TIN, with or without branch code.
            new Rule(Pattern.compile("\\b\\d{3}-\\d{3}-\\d{3}(?:-\\d{3,5})?\\b"), "[TIN]"),
            // Account numbers given as a field: keep the last four digits.
            new Rule(Pattern.compile("(?i)(\"?\\baccount_?(?:no|number|num)\"?\\s*[:=]\\s*\"?)[\\d -]*(\\d{4})"), "$1****$2"),
            // E-mail addresses: first character and domain only.
            new Rule(Pattern.compile("\\b([A-Za-z0-9])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b"), "$1***@$2"));

    private LogMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(out);
            if (m.find()) {
                out = m.replaceAll(r.replacement());
            }
        }
        return out;
    }
}
