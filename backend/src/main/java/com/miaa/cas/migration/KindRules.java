package com.miaa.cas.migration;

import com.miaa.cas.journal.web.JournalDtos.LineRequest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validation, reconciliation and application of one kind of migration batch. Implementations check rows
 * against current CAS data, state what each row contributes to the opening entry (keyed by account or
 * subsidiary account), read the same keys from the opening entry, and create the sub-ledger records.
 */
interface KindRules {

    /** One reconciliation line: what the batch or the opening entry holds for a key. */
    record Side(String code, String name, BigDecimal amount) {
    }

    /** The posted opening entry a batch belongs to. */
    record Entry(UUID id, String number, LocalDate date, UUID periodId) {
    }

    /** Who applies the batch, on whose preparation, and when. */
    record Context(UUID batchId, String description, Entry entry, UUID preparerId, String preparer, Timestamp now) {
    }

    /** Row problems (one list per row, in file order), file-level problems, the control total and parsed rows. */
    record Checked(List<List<String>> rowErrors, List<String> batchErrors, BigDecimal total, Map<String, Side> batch,
            List<?> items, List<LineRequest> lines) {

        int errorCount() {
            return (int) rowErrors.stream().filter(e -> !e.isEmpty()).count() + batchErrors.size();
        }
    }

    MigrationService.Kind kind();

    Checked check(List<MigrationCsv.Row> rows, LocalDate cutover);

    /** What the reconciliation compares against, as shown to the user. */
    String basis();

    /** Amounts per key in the opening entry. */
    Map<String, Side> ledger(Entry entry);

    void apply(Context ctx, Checked checked);

    /** Adds {@code amount} to the batch side of {@code key}. */
    static void add(Map<String, Side> sides, String key, String code, String name, BigDecimal amount) {
        sides.merge(key, new Side(code, name, amount), (a, b) -> new Side(a.code(), a.name(), a.amount().add(b.amount())));
    }

    static Map<String, Side> sides() {
        return new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------ cell parsers shared by the kinds

    /** A non-negative amount with at most two decimals and no sign or thousands separators. */
    static BigDecimal amount(String s, String label, List<String> errors, boolean optional) {
        return decimal(s, label, errors, optional, 17, 2);
    }

    static BigDecimal decimal(String s, String label, List<String> errors, boolean optional, int integers, int decimals) {
        if (s.isEmpty()) {
            if (!optional) {
                errors.add(label + " is required.");
            }
            return null;
        }
        if (!s.matches("\\d{1," + integers + "}(\\.\\d{1," + decimals + "})?")) {
            errors.add(label + " must be a number with at most " + switch (decimals) {
                case 2 -> "two";
                case 4 -> "four";
                case 6 -> "six";
                default -> String.valueOf(decimals);
            } + " decimals, without sign or thousands separators.");
            return null;
        }
        return new BigDecimal(s).setScale(decimals);
    }

    static Integer integer(String s, String label, List<String> errors, int min, int max) {
        if (!s.matches("\\d{1,4}") || Integer.parseInt(s) < min || Integer.parseInt(s) > max) {
            errors.add(label + " must be a whole number from " + min + " to " + max + ".");
            return null;
        }
        return Integer.parseInt(s);
    }

    static LocalDate date(String s, String label, List<String> errors) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ex) {
            errors.add(label + " must be a date in yyyy-MM-dd form.");
            return null;
        }
    }

    /** A text cell: required or optional, at most {@code max} characters; empty optional cells become null. */
    static String text(String s, String label, List<String> errors, boolean required, int max) {
        if (s.isEmpty()) {
            if (required) {
                errors.add(label + " is required.");
            }
            return null;
        }
        if (s.length() > max) {
            errors.add(label + " is longer than " + max + " characters.");
        }
        return s;
    }
}
