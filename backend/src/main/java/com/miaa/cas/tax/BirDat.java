package com.miaa.cas.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Writing BIR alphalist DAT files, whose file structures are prescribed by RMC 7-2021 Annex A and whose names by Annex B.
 * One record per line; fields are separated by commas, names are in double quotes and amounts have two decimals without
 * thousands separators, as the Alphalist Data Entry and Validation Module reads them. The annex gives each field's width;
 * a name longer than its width is cut to it.
 */
final class BirDat {

    /** A TIN split the way the file structures want it: nine digits and a four-digit branch code. */
    record Tin(String number, String branch) {
    }

    private final List<String> lines = new ArrayList<>();

    BirDat record(Object... fields) {
        StringJoiner j = new StringJoiner(",");
        for (Object f : fields) {
            j.add(f == null ? "" : f.toString());
        }
        lines.add(j.toString());
        return this;
    }

    /** CRLF line ends, as the module, a Windows program, writes them. */
    byte[] bytes() {
        return (String.join("\r\n", lines) + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** A name field: in double quotes, cut to its width, with quotes and line breaks removed and accents dropped. */
    static String text(String value, int width) {
        String v = value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[\"\\r\\n]", " ").replaceAll("[^\\x20-\\x7E]", "").trim().replaceAll(" {2,}", " ");
        return "\"" + (v.length() > width ? v.substring(0, width).trim() : v) + "\"";
    }

    /** 9(11).99 */
    static String amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 9(2).99: a rate of 0.02 is written 2.00. */
    static String rate(BigDecimal fraction) {
        return fraction.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The nine-digit TIN and four-digit branch code in a TIN as stored (000-000-000 with an optional branch of three to five
     * digits), or null when it has fewer than nine digits or a branch code above 9999.
     */
    static Tin tin(String stored) {
        if (stored == null) {
            return null;
        }
        String digits = stored.replaceAll("[^0-9]", "");
        if (digits.length() < 9) {
            return null;
        }
        int branch = digits.length() == 9 ? 0 : Integer.parseInt(digits.substring(9));
        return branch > 9999 ? null : new Tin(digits.substring(0, 9), String.format("%04d", branch));
    }
}
