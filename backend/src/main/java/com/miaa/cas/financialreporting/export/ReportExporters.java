package com.miaa.cas.financialreporting.export;

import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Renders a {@link ReportTable} as CSV, XLSX or PDF. */
public final class ReportExporters {

    private ReportExporters() {
    }

    public static byte[] export(ReportTable t, String format) {
        return switch (format) {
            case "CSV" -> csv(t);
            case "XLSX" -> xlsx(t);
            case "PDF" -> pdf(t);
            default -> throw new IllegalArgumentException("Unknown format " + format);
        };
    }

    public static String contentType(String format) {
        return switch (format) {
            case "CSV" -> "text/csv; charset=UTF-8";
            case "XLSX" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/pdf";
        };
    }

    // ------------------------------------------------------------------ CSV

    static byte[] csv(ReportTable t) {
        StringBuilder sb = new StringBuilder("﻿");
        line(sb, t.title());
        t.subtitles().forEach(s -> line(sb, s));
        sb.append("\r\n");
        sb.append(field(t.labelHeader()));
        t.columns().forEach(c -> sb.append(',').append(field(c)));
        sb.append("\r\n");
        for (ReportTable.Row r : t.rows()) {
            sb.append(field("  ".repeat(r.indent()) + r.label()));
            for (Object v : r.values()) {
                sb.append(',').append(v == null ? "" : v instanceof BigDecimal b ? b.toPlainString() : field(v.toString()));
            }
            sb.append("\r\n");
        }
        sb.append("\r\n");
        t.notes().forEach(n -> line(sb, n));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder sb, String text) {
        sb.append(field(text)).append("\r\n");
    }

    /** Quotes the field and neutralises spreadsheet formula injection (=, +, -, @ at the start). */
    static String field(String s) {
        String v = s == null ? "" : s;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    // ------------------------------------------------------------------ XLSX

    static byte[] xlsx(ReportTable t) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Report");
            XSSFFont boldFont = wb.createFont();
            boldFont.setBold(true);
            CellStyle bold = wb.createCellStyle();
            bold.setFont(boldFont);
            short fmt = wb.createDataFormat().getFormat("#,##0.00;(#,##0.00)");
            CellStyle num = wb.createCellStyle();
            num.setDataFormat(fmt);
            CellStyle numBold = wb.createCellStyle();
            numBold.setDataFormat(fmt);
            numBold.setFont(boldFont);
            numBold.setBorderTop(BorderStyle.THIN);
            int r = 0;
            Row title = sh.createRow(r++);
            title.createCell(0).setCellValue(t.title());
            title.getCell(0).setCellStyle(bold);
            for (String s : t.subtitles()) {
                sh.createRow(r++).createCell(0).setCellValue(s);
            }
            r++;
            Row head = sh.createRow(r++);
            head.createCell(0).setCellValue(t.labelHeader());
            head.getCell(0).setCellStyle(bold);
            for (int c = 0; c < t.columns().size(); c++) {
                head.createCell(c + 1).setCellValue(t.columns().get(c));
                head.getCell(c + 1).setCellStyle(bold);
            }
            for (ReportTable.Row row : t.rows()) {
                Row x = sh.createRow(r++);
                CellStyle labelStyle = wb.createCellStyle();
                labelStyle.setIndention((short) row.indent());
                if (row.bold()) {
                    labelStyle.setFont(boldFont);
                }
                x.createCell(0).setCellValue(row.label());
                x.getCell(0).setCellStyle(labelStyle);
                for (int c = 0; c < row.values().size(); c++) {
                    Object v = row.values().get(c);
                    if (v instanceof BigDecimal b) {
                        x.createCell(c + 1).setCellValue(b.doubleValue());
                        x.getCell(c + 1).setCellStyle(row.bold() ? numBold : num);
                    } else if (v != null) {
                        // Text is written as a string cell, never as a formula.
                        x.createCell(c + 1).setCellValue(v.toString());
                    }
                }
            }
            r++;
            for (String n : t.notes()) {
                sh.createRow(r++).createCell(0).setCellValue(n);
            }
            sh.setColumnWidth(0, 60 * 256);
            for (int c = 1; c <= t.columns().size(); c++) {
                sh.setColumnWidth(c, 20 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ PDF

    static byte[] pdf(ReportTable t) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(t.columns().size() > 3 ? PageSize.A4.rotate() : PageSize.A4, 36, 36, 36, 36);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font titleFont = new Font(Font.HELVETICA, 14, Font.BOLD);
        Font small = new Font(Font.HELVETICA, 9);
        Font smallBold = new Font(Font.HELVETICA, 9, Font.BOLD);
        doc.add(new Paragraph(t.title(), titleFont));
        for (String s : t.subtitles()) {
            doc.add(new Paragraph(s, small));
        }
        doc.add(new Paragraph(" "));
        PdfPTable table = new PdfPTable(t.columns().size() + 1);
        table.setWidthPercentage(100);
        float[] widths = new float[t.columns().size() + 1];
        widths[0] = 3f;
        for (int i = 1; i < widths.length; i++) {
            widths[i] = 1.2f;
        }
        table.setWidths(widths);
        table.setHeaderRows(1);
        table.addCell(cell(t.labelHeader(), smallBold, Element.ALIGN_LEFT, 0));
        for (String c : t.columns()) {
            table.addCell(cell(c, smallBold, Element.ALIGN_RIGHT, 0));
        }
        DecimalFormat money = new DecimalFormat("#,##0.00;(#,##0.00)");
        for (ReportTable.Row r : t.rows()) {
            Font f = r.bold() ? smallBold : small;
            table.addCell(cell(r.label(), f, Element.ALIGN_LEFT, r.indent()));
            for (Object v : r.values()) {
                if (v instanceof BigDecimal b) {
                    table.addCell(cell(money.format(b), f, Element.ALIGN_RIGHT, 0));
                } else {
                    table.addCell(cell(v == null ? "" : v.toString(), f, Element.ALIGN_LEFT, 0));
                }
            }
        }
        doc.add(table);
        doc.add(new Paragraph(" "));
        for (String n : t.notes()) {
            doc.add(new Paragraph(n, small));
        }
        doc.close();
        return out.toByteArray();
    }

    private static PdfPCell cell(String text, Font f, int align, int indent) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setHorizontalAlignment(align);
        c.setBorder(PdfPCell.BOTTOM);
        c.setBorderWidthBottom(0.2f);
        c.setPaddingLeft(4 + indent * 10f);
        return c;
    }
}
