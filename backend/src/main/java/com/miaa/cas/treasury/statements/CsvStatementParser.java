package com.miaa.cas.treasury.statements;

import com.miaa.cas.common.csv.Csv;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CAS generic CSV layout (bank-specific layouts are mapped to this until the banks' formats are
 * confirmed, C-11). Header row required: {@code date,description,reference,amount} with a signed
 * amount, or {@code date,description,reference,debit,credit}; an optional {@code balance} column is
 * checked as a running balance. Dates are ISO (yyyy-MM-dd); amounts use '.' and no thousands separators.
 * CSV carries no balances, so the opening and closing balances are entered with the upload.
 */
public final class CsvStatementParser {

    private CsvStatementParser() {
    }

    public static ParsedStatement parse(byte[] content, BigDecimal opening, BigDecimal closing) {
        if (opening == null || closing == null) {
            throw new StatementFormatException("Enter the statement's opening and closing balances for a CSV upload.");
        }
        List<String> rows = new String(content, StandardCharsets.UTF_8).replace("\uFEFF", "").lines()
                .filter(l -> !l.isBlank()).toList();
        if (rows.size() < 2) {
            throw new StatementFormatException("The file needs a header row and at least one transaction.");
        }
        List<String> header = split(rows.get(0)).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        int date = header.indexOf("date");
        int desc = header.indexOf("description");
        int ref = header.indexOf("reference");
        int amount = header.indexOf("amount");
        int debit = header.indexOf("debit");
        int credit = header.indexOf("credit");
        int balance = header.indexOf("balance");
        if (date < 0 || desc < 0 || (amount < 0 && (debit < 0 || credit < 0))) {
            throw new StatementFormatException("Header must contain date, description and either amount or debit and credit.");
        }
        List<ParsedStatement.Line> lines = new ArrayList<>();
        BigDecimal running = opening;
        for (int i = 1; i < rows.size(); i++) {
            int rowNo = i + 1;
            List<String> f = split(rows.get(i));
            if (f.size() < header.size()) {
                throw new StatementFormatException("Row " + rowNo + ": expected " + header.size() + " columns, found " + f.size() + ".");
            }
            LocalDate d;
            try {
                d = LocalDate.parse(f.get(date).trim());
            } catch (DateTimeParseException e) {
                throw new StatementFormatException("Row " + rowNo + ": date must be yyyy-MM-dd.");
            }
            BigDecimal amt;
            if (amount >= 0) {
                amt = number(f.get(amount), rowNo);
            } else {
                BigDecimal dr = f.get(debit).isBlank() ? BigDecimal.ZERO : number(f.get(debit), rowNo);
                BigDecimal cr = f.get(credit).isBlank() ? BigDecimal.ZERO : number(f.get(credit), rowNo);
                if (dr.signum() < 0 || cr.signum() < 0 || (dr.signum() > 0 && cr.signum() > 0)) {
                    throw new StatementFormatException("Row " + rowNo + ": give either a debit or a credit, as positive amounts.");
                }
                amt = cr.subtract(dr);
            }
            if (amt.signum() == 0) {
                throw new StatementFormatException("Row " + rowNo + ": the amount is zero.");
            }
            running = running.add(amt);
            if (balance >= 0 && !f.get(balance).isBlank() && number(f.get(balance), rowNo).compareTo(running) != 0) {
                throw new StatementFormatException("Row " + rowNo + ": running balance " + f.get(balance).trim()
                        + " does not agree with the computed " + running.toPlainString() + ".");
            }
            String description = f.get(desc).trim();
            lines.add(new ParsedStatement.Line(d, d, amt, description.isEmpty() ? "(no description)" : description,
                    ref >= 0 && !f.get(ref).isBlank() ? f.get(ref).trim() : null));
        }
        LocalDate start = lines.stream().map(ParsedStatement.Line::valueDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate end = lines.stream().map(ParsedStatement.Line::valueDate).max(LocalDate::compareTo).orElseThrow();
        return new ParsedStatement(null, null, null, start, end, opening, closing, lines);
    }

    private static BigDecimal number(String s, int row) {
        String v = s.trim();
        if (!v.matches("^-?\\d{1,17}(\\.\\d{1,2})?$")) {
            throw new StatementFormatException("Row " + row + ": '" + v + "' is not an amount (use 1234.50, no thousands separators).");
        }
        return new BigDecimal(v).setScale(2);
    }

    /** RFC 4180 field splitting with quoted fields and doubled quotes. */
    static List<String> split(String line) {
        return Csv.split(line);
    }
}
