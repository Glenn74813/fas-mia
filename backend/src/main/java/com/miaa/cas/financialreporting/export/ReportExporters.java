package com.miaa.cas.financialreporting.export;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Renders a {@link ReportTable} as CSV, XLSX or PDF on the organisation's letterhead: its name, address and TIN at the
 * top, then the report title and subtitles, the table and the notes. A subtitle repeating the organisation's name is
 * left out, since the letterhead already shows it.
 */
public final class ReportExporters {

    private static final Color INK = new Color(0x0f, 0x2a, 0x4a);
    private static final Color MUTED = new Color(0x55, 0x5f, 0x6d);
    private static final Color HEADER_FILL = new Color(0xe8, 0xee, 0xf5);

    private ReportExporters() {
    }

    public static byte[] export(ReportTable t, String format) {
        return export(t, format, Letterhead.DEFAULT);
    }

    public static byte[] export(ReportTable t, String format, Letterhead letterhead) {
        return switch (format) {
            case "CSV" -> csv(t, letterhead);
            case "XLSX" -> xlsx(t, letterhead);
            case "PDF" -> pdf(t, letterhead);
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

    private static List<String> subtitles(ReportTable t, Letterhead lh) {
        return t.subtitles().stream().filter(s -> s != null && !s.equalsIgnoreCase(lh.name())).toList();
    }

    // ------------------------------------------------------------------ CSV

    static byte[] csv(ReportTable t, Letterhead lh) {
        StringBuilder sb = new StringBuilder("﻿");
        line(sb, lh.name());
        lh.details().forEach(d -> line(sb, d));
        sb.append("\r\n");
        line(sb, t.title());
        subtitles(t, lh).forEach(s -> line(sb, s));
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

    static byte[] xlsx(ReportTable t, Letterhead lh) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Report");
            int lastCol = t.columns().size();
            XSSFFont boldFont = wb.createFont();
            boldFont.setBold(true);
            XSSFFont nameFont = wb.createFont();
            nameFont.setBold(true);
            nameFont.setFontHeightInPoints((short) 15);
            nameFont.setColor(new org.apache.poi.xssf.usermodel.XSSFColor(INK, null));
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 12);
            XSSFFont mutedFont = wb.createFont();
            mutedFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            CellStyle nameStyle = centred(wb, nameFont);
            CellStyle detailStyle = centred(wb, mutedFont);
            CellStyle ruleStyle = wb.createCellStyle();
            ruleStyle.setBorderBottom(BorderStyle.MEDIUM);
            ruleStyle.setBottomBorderColor(IndexedColors.DARK_BLUE.getIndex());
            CellStyle titleStyle = centred(wb, titleFont);
            CellStyle subtitleStyle = centred(wb, null);
            CellStyle head = wb.createCellStyle();
            head.setFont(boldFont);
            head.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(HEADER_FILL, null));
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            CellStyle headRight = wb.createCellStyle();
            headRight.cloneStyleFrom(head);
            headRight.setAlignment(HorizontalAlignment.RIGHT);
            short fmt = wb.createDataFormat().getFormat("#,##0.00;(#,##0.00)");
            CellStyle num = wb.createCellStyle();
            num.setDataFormat(fmt);
            CellStyle numBold = wb.createCellStyle();
            numBold.setDataFormat(fmt);
            numBold.setFont(boldFont);
            numBold.setBorderTop(BorderStyle.THIN);
            CellStyle noteStyle = wb.createCellStyle();
            noteStyle.setFont(mutedFont);

            int r = 0;
            r = banner(sh, r, lh.name(), nameStyle, lastCol);
            for (String d : lh.details()) {
                r = banner(sh, r, d, detailStyle, lastCol);
            }
            Row rule = sh.createRow(r++);
            for (int c = 0; c <= lastCol; c++) {
                rule.createCell(c).setCellStyle(ruleStyle);
            }
            r++;
            r = banner(sh, r, t.title(), titleStyle, lastCol);
            for (String s : subtitles(t, lh)) {
                r = banner(sh, r, s, subtitleStyle, lastCol);
            }
            r++;
            int headerRow = r;
            Row header = sh.createRow(r++);
            header.createCell(0).setCellValue(t.labelHeader());
            header.getCell(0).setCellStyle(head);
            for (int c = 0; c < t.columns().size(); c++) {
                header.createCell(c + 1).setCellValue(t.columns().get(c));
                header.getCell(c + 1).setCellStyle(headRight);
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
                Row x = sh.createRow(r++);
                x.createCell(0).setCellValue(n);
                x.getCell(0).setCellStyle(noteStyle);
            }
            sh.setColumnWidth(0, 60 * 256);
            for (int c = 1; c <= lastCol; c++) {
                sh.setColumnWidth(c, 18 * 256);
            }
            sh.createFreezePane(0, headerRow + 1);
            sh.setRepeatingRows(new CellRangeAddress(headerRow, headerRow, -1, -1));
            sh.setFitToPage(true);
            sh.setAutobreaks(true);
            PrintSetup print = sh.getPrintSetup();
            print.setLandscape(lastCol > 3);
            print.setPaperSize(PrintSetup.A4_PAPERSIZE);
            print.setFitWidth((short) 1);
            print.setFitHeight((short) 0);
            sh.getFooter().setLeft(lh.name());
            sh.getFooter().setRight("Page &P of &N");
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CellStyle centred(XSSFWorkbook wb, XSSFFont font) {
        CellStyle s = wb.createCellStyle();
        s.setAlignment(HorizontalAlignment.CENTER);
        if (font != null) {
            s.setFont(font);
        }
        return s;
    }

    /** One line centred across the report's columns. */
    private static int banner(Sheet sh, int r, String text, CellStyle style, int lastCol) {
        Row x = sh.createRow(r);
        x.createCell(0).setCellValue(text);
        x.getCell(0).setCellStyle(style);
        if (lastCol > 0) {
            sh.addMergedRegion(new CellRangeAddress(r, r, 0, lastCol));
        }
        return r + 1;
    }

    // ------------------------------------------------------------------ PDF

    static byte[] pdf(ReportTable t, Letterhead lh) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(t.columns().size() > 3 ? PageSize.A4.rotate() : PageSize.A4, 36, 36, 36, 48);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new Footer(lh.name()));
        doc.open();
        Font nameFont = new Font(Font.HELVETICA, 15, Font.BOLD, INK);
        Font detailFont = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
        Font titleFont = new Font(Font.HELVETICA, 12, Font.BOLD);
        Font subtitleFont = new Font(Font.HELVETICA, 9);
        Font small = new Font(Font.HELVETICA, 8.5f);
        Font smallBold = new Font(Font.HELVETICA, 8.5f, Font.BOLD);
        Font note = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);

        doc.add(centred(lh.name(), nameFont, 0));
        for (String d : lh.details()) {
            doc.add(centred(d, detailFont, 0));
        }
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        rule.setSpacingBefore(4);
        PdfPCell line = new PdfPCell();
        line.setBorder(Rectangle.BOTTOM);
        line.setBorderWidthBottom(1.2f);
        line.setBorderColorBottom(INK);
        line.setFixedHeight(2);
        rule.addCell(line);
        doc.add(rule);
        doc.add(centred(t.title(), titleFont, 10));
        for (String s : subtitles(t, lh)) {
            doc.add(centred(s, subtitleFont, 0));
        }
        doc.add(new Paragraph(" ", subtitleFont));

        PdfPTable table = new PdfPTable(t.columns().size() + 1);
        table.setWidthPercentage(100);
        float[] widths = new float[t.columns().size() + 1];
        widths[0] = 3f;
        for (int i = 1; i < widths.length; i++) {
            widths[i] = 1.2f;
        }
        table.setWidths(widths);
        table.setHeaderRows(1);
        table.addCell(headCell(t.labelHeader(), smallBold, Element.ALIGN_LEFT));
        for (String c : t.columns()) {
            table.addCell(headCell(c, smallBold, Element.ALIGN_RIGHT));
        }
        DecimalFormat money = new DecimalFormat("#,##0.00;(#,##0.00)");
        for (ReportTable.Row r : t.rows()) {
            Font f = r.bold() ? smallBold : small;
            table.addCell(cell(r.label(), f, Element.ALIGN_LEFT, r.indent(), r.bold()));
            for (Object v : r.values()) {
                if (v instanceof BigDecimal b) {
                    table.addCell(cell(money.format(b), f, Element.ALIGN_RIGHT, 0, r.bold()));
                } else {
                    table.addCell(cell(v == null ? "" : v.toString(), f, Element.ALIGN_LEFT, 0, r.bold()));
                }
            }
        }
        doc.add(table);
        doc.add(new Paragraph(" ", subtitleFont));
        for (String n : t.notes()) {
            doc.add(new Paragraph(n, note));
        }
        doc.close();
        return out.toByteArray();
    }

    private static Paragraph centred(String text, Font f, float spacingBefore) {
        Paragraph p = new Paragraph(text, f);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingBefore(spacingBefore);
        return p;
    }

    private static PdfPCell headCell(String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setHorizontalAlignment(align);
        c.setBackgroundColor(HEADER_FILL);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderWidthBottom(0.6f);
        c.setBorderColorBottom(INK);
        c.setPaddingTop(4);
        c.setPaddingBottom(5);
        c.setPaddingLeft(4);
        c.setPaddingRight(4);
        return c;
    }

    private static PdfPCell cell(String text, Font f, int align, int indent, boolean total) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setHorizontalAlignment(align);
        c.setBorder(total ? Rectangle.TOP | Rectangle.BOTTOM : Rectangle.BOTTOM);
        c.setBorderWidthTop(total ? 0.6f : 0);
        c.setBorderWidthBottom(0.2f);
        c.setBorderColorBottom(Color.LIGHT_GRAY);
        c.setPaddingTop(3);
        c.setPaddingBottom(4);
        c.setPaddingLeft(4 + indent * 10f);
        c.setPaddingRight(4);
        return c;
    }

    /** The organisation's name and the page number at the foot of every page. */
    private static final class Footer extends PdfPageEventHelper {

        private final String name;

        Footer(String name) {
            this.name = name;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Font f = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, MUTED);
            float y = document.bottom() - 20;
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(name, f), document.left(), y, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), f), document.right(), y, 0);
        }
    }
}
