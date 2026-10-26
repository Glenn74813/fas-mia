package com.miaa.cas.treasury.statements;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SWIFT MT940 customer statement (one statement per file). Reads :20: reference, :25: account,
 * :60F:/:60M: opening, :61: entries with their :86: information, and :62F:/:62M: closing balance.
 * Bank-specific :86: sub-field layouts are kept as free text.
 */
public final class Mt940Parser {

    private static final Pattern BALANCE = Pattern.compile("^([CD])(\\d{6})([A-Z]{3})(\\d[\\d,]*)$");
    private static final Pattern ENTRY = Pattern.compile(
            "^(\\d{6})(\\d{4})?(RC|RD|C|D)([A-Z])?(\\d[\\d,]*)([NSF][A-Z0-9]{3})([^/]{0,16})(?://(.{0,16}))?(.*)$");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private Mt940Parser() {
    }

    public static ParsedStatement parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8).replace("\r", "");
        List<String[]> fields = fields(text);
        String reference = null;
        String account = null;
        String currency = null;
        BigDecimal opening = null;
        BigDecimal closing = null;
        LocalDate openingDate = null;
        LocalDate closingDate = null;
        List<ParsedStatement.Line> lines = new ArrayList<>();
        String[] pending = null;
        int statements = 0;
        for (String[] f : fields) {
            String tag = f[0];
            String value = f[1];
            switch (tag) {
                case "20" -> {
                    statements++;
                    reference = value.trim();
                }
                case "25" -> account = value.trim();
                case "60F", "60M" -> {
                    Matcher m = balance(value, tag);
                    opening = amount(m.group(4), m.group(1).equals("D"));
                    openingDate = date(m.group(2));
                    currency = m.group(3);
                }
                case "61" -> {
                    if (pending != null) {
                        lines.add(entry(pending, null));
                    }
                    pending = new String[] {value};
                }
                case "86" -> {
                    if (pending != null) {
                        lines.add(entry(pending, value));
                        pending = null;
                    }
                }
                case "62F", "62M" -> {
                    Matcher m = balance(value, tag);
                    closing = amount(m.group(4), m.group(1).equals("D"));
                    closingDate = date(m.group(2));
                    if (!m.group(3).equals(currency)) {
                        throw new StatementFormatException(":62: currency differs from :60:.");
                    }
                }
                default -> {
                    // :28C:, :64:, :65: and others are not needed for reconciliation.
                }
            }
        }
        if (pending != null) {
            lines.add(entry(pending, null));
        }
        if (statements != 1) {
            throw new StatementFormatException("Expected exactly one statement (:20:) in the file, found " + statements + ".");
        }
        if (opening == null || closing == null) {
            throw new StatementFormatException("Opening (:60F:) and closing (:62F:) balances are required.");
        }
        LocalDate start = lines.stream().map(ParsedStatement.Line::valueDate).min(LocalDate::compareTo).orElse(openingDate);
        LocalDate end = closingDate;
        if (start.isAfter(end)) {
            start = end;
        }
        return new ParsedStatement(account, currency, reference, start, end, opening, closing, lines);
    }

    /** Splits the text block into (tag, value) pairs; continuation lines belong to the previous field. */
    private static List<String[]> fields(String text) {
        List<String[]> out = new ArrayList<>();
        Pattern tagLine = Pattern.compile("^:(\\d{2}[A-Z]?):(.*)$");
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("{") || line.equals("-}") || line.equals("-")) {
                continue;
            }
            Matcher m = tagLine.matcher(line);
            if (m.matches()) {
                out.add(new String[] {m.group(1), m.group(2)});
            } else if (!out.isEmpty()) {
                String[] last = out.get(out.size() - 1);
                last[1] = last[1] + "\n" + line;
            }
        }
        return out;
    }

    private static ParsedStatement.Line entry(String[] f61, String info) {
        String first = f61[0].split("\n", 2)[0];
        String supplementary = f61[0].contains("\n") ? f61[0].split("\n", 2)[1].trim() : "";
        Matcher m = ENTRY.matcher(first);
        if (!m.matches()) {
            throw new StatementFormatException("Unreadable :61: entry '" + first + "'.");
        }
        LocalDate value = date(m.group(1));
        LocalDate booking = null;
        if (m.group(2) != null) {
            booking = LocalDate.of(value.getYear(), Integer.parseInt(m.group(2).substring(0, 2)),
                    Integer.parseInt(m.group(2).substring(2)));
            // An entry date in January for a December value date belongs to the next year, and vice versa.
            if (booking.isBefore(value.minusMonths(6))) {
                booking = booking.plusYears(1);
            } else if (booking.isAfter(value.plusMonths(6))) {
                booking = booking.minusYears(1);
            }
        }
        String mark = m.group(3);
        boolean debit = mark.equals("D") || mark.equals("RC");
        BigDecimal amount = amount(m.group(5), debit);
        String customerRef = m.group(7).trim();
        String bankRef = m.group(8) == null ? null : m.group(8).trim();
        String reference = !customerRef.isEmpty() && !customerRef.equals("NONREF") ? customerRef : bankRef;
        String description = String.join(" ", info == null ? "" : info.replace("\n", " ").trim(), supplementary).trim();
        if (description.isEmpty()) {
            description = m.group(6) + (reference == null ? "" : " " + reference);
        }
        return new ParsedStatement.Line(value, booking, amount, cut(description, 500), reference == null ? null : cut(reference, 100));
    }

    private static Matcher balance(String value, String tag) {
        Matcher m = BALANCE.matcher(value.trim());
        if (!m.matches()) {
            throw new StatementFormatException("Unreadable :" + tag + ": balance '" + value.trim() + "'.");
        }
        return m;
    }

    private static BigDecimal amount(String swift, boolean negative) {
        String v = swift.replace(',', '.');
        if (v.endsWith(".")) {
            v = v + "0";
        }
        if (v.chars().filter(c -> c == '.').count() != 1) {
            throw new StatementFormatException("Unreadable amount '" + swift + "'.");
        }
        BigDecimal a = new BigDecimal(v);
        if (a.scale() > 2) {
            throw new StatementFormatException("Amount '" + swift + "' has more than two decimals.");
        }
        a = a.setScale(2);
        return negative ? a.negate() : a;
    }

    private static LocalDate date(String yymmdd) {
        return LocalDate.parse(yymmdd, YYMMDD);
    }

    static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
