package com.miaa.cas.security.password;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.config.CasProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Password strength rules (MS 9.1). The bundled blocked-password list is a small seed; replace
 * {@code security/blocked-passwords.txt} with an approved, larger breached-password corpus.
 */
@Component
public class PasswordPolicy {

    private final int minLength;
    private final int maxLength;
    private final Set<String> blocked;

    public PasswordPolicy(CasProperties properties) {
        this.minLength = properties.password().minLength();
        this.maxLength = properties.password().maxLength();
        this.blocked = loadBlocked();
    }

    public List<String> violations(String password, String username, String email) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.length() < minLength) {
            problems.add("Password must be at least " + minLength + " characters long.");
            return problems;
        }
        if (password.length() > maxLength) {
            problems.add("Password must be at most " + maxLength + " characters long.");
        }
        int classes = 0;
        classes += password.chars().anyMatch(Character::isUpperCase) ? 1 : 0;
        classes += password.chars().anyMatch(Character::isLowerCase) ? 1 : 0;
        classes += password.chars().anyMatch(Character::isDigit) ? 1 : 0;
        classes += password.chars().anyMatch(c -> !Character.isLetterOrDigit(c)) ? 1 : 0;
        if (classes < 3) {
            problems.add("Password must contain at least three of: upper-case, lower-case, digit, symbol.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase(Locale.ROOT))) {
            problems.add("Password must not contain the username.");
        }
        if (email != null && email.contains("@")) {
            String local = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
            if (local.length() >= 3 && lower.contains(local)) {
                problems.add("Password must not contain the e-mail name.");
            }
        }
        if (blocked.contains(lower)) {
            problems.add("Password is too common.");
        }
        return problems;
    }

    public void enforce(String password, String username, String email) {
        List<String> problems = violations(password, username, email);
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY_VIOLATION, "The password does not meet the policy.",
                    Map.of("violations", problems));
        }
    }

    private static Set<String> loadBlocked() {
        Set<String> result = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("security/blocked-passwords.txt").getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.toLowerCase(Locale.ROOT)).forEach(result::add);
        } catch (IOException e) {
            throw new IllegalStateException("Blocked password list missing", e);
        }
        return result;
    }
}
