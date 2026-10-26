package com.miaa.cas.financialreporting.export;

import java.util.List;

/**
 * Presentation-neutral report content that every exporter renders the same way. The first column is the
 * row label; each further cell is either an amount ({@link java.math.BigDecimal}, right-aligned) or text.
 */
public record ReportTable(String title, List<String> subtitles, String labelHeader, List<String> columns, List<Row> rows,
        List<String> notes) {

    /** A label and one cell per column; cells may be null (blank). */
    public record Row(String label, int indent, boolean bold, List<?> values) {
    }
}
