package com.miaa.cas.treasury;

import com.miaa.cas.treasury.TreasuryDtos.DailyCollectionReport;
import com.miaa.cas.treasury.TreasuryDtos.DcrLine;
import com.miaa.cas.treasury.TreasuryDtos.MethodTotal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The daily collection report as an Excel form: a header, one row per receipt in the columns of the Daily collections page,
 * totals by payment method, the cash summary and lines for signatures. Cancelled receipts are listed with their reason but
 * left out of every total. Set up to print landscape on one page width with the column headings repeated.
 */
final class DcrWorkbook {

    private static final String[] COLUMNS = {"Receipt", "Customer", "Method", "Collector", "Status", "Deposit", "Amount", "Tax withheld"};
    private static final int[] WIDTHS = {20, 38, 26, 22, 14, 20, 16, 16};
    private static final int LAST = COLUMNS.length - 1;
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private DcrWorkbook() {
    }

    static byte[] render(DailyCollectionReport r, String organization, String generatedBy, String generatedAt) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles s = new Styles(wb);
            XSSFSheet sh = wb.createSheet("DCR " + r.date());
            for (int c = 0; c < WIDTHS.length; c++) {
                sh.setColumnWidth(c, WIDTHS[c] * 256);
            }
            int row = 0;
            row = title(sh, row, organization == null ? "" : organization, s.org);
            row = title(sh, row, "DAILY COLLECTION REPORT", s.title);
            row = title(sh, row, "Collections of " + LONG_DATE.format(r.date()), s.centre);
            row++;
            label(sh, row++, s, "Collecting officer", r.collector() == null ? "All collectors" : r.collector());
            label(sh, row++, s, "Receipts issued", r.firstNumber() == null ? "None" : r.firstNumber() + " to " + r.lastNumber());
            label(sh, row++, s, "Receipts cancelled", String.valueOf(r.cancelled()));
            row++;

            int header = row;
            Row h = sh.createRow(row++);
            for (int c = 0; c < COLUMNS.length; c++) {
                h.createCell(c).setCellValue(COLUMNS[c]);
                h.getCell(c).setCellStyle(c >= 6 ? s.headRight : s.head);
            }
            for (DcrLine l : r.lines()) {
                boolean cancelled = "CANCELLED".equals(l.status());
                Row x = sh.createRow(row++);
                text(x, 0, l.receiptNumber(), s.cell);
                text(x, 1, l.customerName(), s.cell);
                text(x, 2, l.paymentMethod().replace('_', ' ') + (l.paymentReference() == null ? "" : " " + l.paymentReference()), s.cell);
                text(x, 3, l.recordedBy(), s.cell);
                text(x, 4, cancelled && l.cancelReason() != null ? "CANCELLED: " + l.cancelReason() : l.status(), s.cell);
                text(x, 5, l.depositNumber() == null ? "—" : l.depositNumber(), s.cell);
                amount(x, 6, l.amount(), cancelled ? s.struck : s.money);
                amount(x, 7, l.taxWithheld(), cancelled ? s.struck : s.money);
            }
            if (r.lines().isEmpty()) {
                Row x = sh.createRow(row++);
                text(x, 0, "No receipts on this date.", s.cell);
                sh.addMergedRegion(new CellRangeAddress(x.getRowNum(), x.getRowNum(), 0, LAST));
            }
            Row total = sh.createRow(row++);
            text(total, 5, "Total collected", s.bold);
            amount(total, 6, r.totalCollected(), s.moneyTotal);
            amount(total, 7, r.totalTaxWithheld(), s.moneyTotal);
            row++;

            row = section(sh, row, s, "Summary by payment method");
            for (MethodTotal m : r.totals()) {
                Row x = sh.createRow(row++);
                text(x, 0, m.paymentMethod().replace('_', ' '), s.cell);
                text(x, 1, m.count() + (m.count() == 1 ? " receipt" : " receipts"), s.cell);
                amount(x, 6, m.amount(), s.money);
            }
            row = summary(sh, row, s, "Total collected", r.totalCollected(), true);
            row = summary(sh, row, s, "Tax withheld (certificates received)", r.totalTaxWithheld(), false);
            row = summary(sh, row, s, "Deposited", r.totalCollected().subtract(r.undeposited()), false);
            row = summary(sh, row, s, "Not yet deposited (cash and checks on hand)", r.undeposited(), true);
            row++;

            row = section(sh, row, s, "Certification");
            Row cert = sh.createRow(row++);
            text(cert, 0, "I certify that the above is a true and complete report of the collections for the day.", s.wrap);
            sh.addMergedRegion(new CellRangeAddress(cert.getRowNum(), cert.getRowNum(), 0, LAST));
            row += 2;
            Row lines = sh.createRow(row++);
            Row names = sh.createRow(row++);
            String[] signers = {"Prepared by (collecting officer)", "Verified by", "Received by (accounting)"};
            int[][] spans = {{0, 1}, {3, 4}, {6, 7}};
            for (int i = 0; i < signers.length; i++) {
                for (int c = spans[i][0]; c <= spans[i][1]; c++) {
                    lines.createCell(c).setCellStyle(s.signLine);
                }
                text(names, spans[i][0], signers[i], s.centreSmall);
                sh.addMergedRegion(new CellRangeAddress(names.getRowNum(), names.getRowNum(), spans[i][0], spans[i][1]));
            }
            row++;
            for (String note : List.of(r.note() == null ? "" : r.note(), "Generated by " + generatedBy + " on " + generatedAt + ".")) {
                if (!note.isEmpty()) {
                    Row x = sh.createRow(row++);
                    text(x, 0, note, s.note);
                    sh.addMergedRegion(new CellRangeAddress(x.getRowNum(), x.getRowNum(), 0, LAST));
                }
            }

            sh.createFreezePane(0, header + 1);
            sh.setRepeatingRows(new CellRangeAddress(header, header, 0, LAST));
            PrintSetup ps = sh.getPrintSetup();
            ps.setLandscape(true);
            ps.setFitWidth((short) 1);
            ps.setFitHeight((short) 0);
            sh.setFitToPage(true);
            sh.setHorizontallyCenter(true);
            sh.setMargin(PageMargin.LEFT, 0.4);
            sh.setMargin(PageMargin.RIGHT, 0.4);
            sh.getFooter().setCenter("Page &P of &N");
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int title(XSSFSheet sh, int row, String value, CellStyle style) {
        Row x = sh.createRow(row);
        text(x, 0, value, style);
        sh.addMergedRegion(new CellRangeAddress(row, row, 0, LAST));
        return row + 1;
    }

    private static void label(XSSFSheet sh, int row, Styles s, String label, String value) {
        Row x = sh.createRow(row);
        text(x, 0, label, s.bold);
        text(x, 1, value, s.cell);
    }

    private static int section(XSSFSheet sh, int row, Styles s, String name) {
        Row x = sh.createRow(row);
        for (int c = 0; c <= LAST; c++) {
            x.createCell(c).setCellStyle(s.section);
        }
        x.getCell(0).setCellValue(name);
        return row + 1;
    }

    private static int summary(XSSFSheet sh, int row, Styles s, String label, BigDecimal value, boolean bold) {
        Row x = sh.createRow(row);
        text(x, 0, label, bold ? s.bold : s.cell);
        amount(x, 6, value, bold ? s.moneyTotal : s.money);
        return row + 1;
    }

    private static void text(Row x, int col, String value, CellStyle style) {
        // Always a string cell: text from a receipt is never treated as a formula.
        x.createCell(col).setCellValue(value == null ? "" : value);
        x.getCell(col).setCellStyle(style);
    }

    private static void amount(Row x, int col, BigDecimal value, CellStyle style) {
        x.createCell(col).setCellValue(value == null ? 0 : value.doubleValue());
        x.getCell(col).setCellStyle(style);
    }

    private static final class Styles {
        final CellStyle org;
        final CellStyle title;
        final CellStyle centre;
        final CellStyle centreSmall;
        final CellStyle head;
        final CellStyle headRight;
        final CellStyle cell;
        final CellStyle wrap;
        final CellStyle bold;
        final CellStyle money;
        final CellStyle moneyTotal;
        final CellStyle struck;
        final CellStyle section;
        final CellStyle signLine;
        final CellStyle note;

        Styles(XSSFWorkbook wb) {
            short peso = wb.createDataFormat().getFormat("\"₱\"#,##0.00;-\"₱\"#,##0.00");
            XSSFFont boldFont = font(wb, 10, true, false);
            org = style(wb, font(wb, 12, true, false), HorizontalAlignment.CENTER);
            title = style(wb, font(wb, 14, true, false), HorizontalAlignment.CENTER);
            centre = style(wb, font(wb, 10, false, false), HorizontalAlignment.CENTER);
            centreSmall = style(wb, font(wb, 9, false, false), HorizontalAlignment.CENTER);
            head = style(wb, font(wb, 9, true, false), HorizontalAlignment.LEFT);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            headRight = style(wb, font(wb, 9, true, false), HorizontalAlignment.RIGHT);
            headRight.cloneStyleFrom(head);
            headRight.setAlignment(HorizontalAlignment.RIGHT);
            cell = style(wb, font(wb, 10, false, false), HorizontalAlignment.LEFT);
            wrap = style(wb, font(wb, 10, false, false), HorizontalAlignment.LEFT);
            wrap.setWrapText(true);
            bold = style(wb, boldFont, HorizontalAlignment.LEFT);
            money = style(wb, font(wb, 10, false, false), HorizontalAlignment.RIGHT);
            money.setDataFormat(peso);
            moneyTotal = style(wb, boldFont, HorizontalAlignment.RIGHT);
            moneyTotal.setDataFormat(peso);
            moneyTotal.setBorderTop(BorderStyle.THIN);
            moneyTotal.setBorderBottom(BorderStyle.DOUBLE);
            struck = style(wb, font(wb, 10, false, true), HorizontalAlignment.RIGHT);
            struck.setDataFormat(peso);
            section = style(wb, boldFont, HorizontalAlignment.LEFT);
            section.setBorderBottom(BorderStyle.THIN);
            signLine = style(wb, font(wb, 10, false, false), HorizontalAlignment.CENTER);
            signLine.setBorderBottom(BorderStyle.THIN);
            note = style(wb, font(wb, 8, false, false), HorizontalAlignment.LEFT);
        }

        private static XSSFFont font(XSSFWorkbook wb, int size, boolean bold, boolean strike) {
            XSSFFont f = wb.createFont();
            f.setFontHeightInPoints((short) size);
            f.setBold(bold);
            f.setStrikeout(strike);
            return f;
        }

        private static CellStyle style(XSSFWorkbook wb, XSSFFont font, HorizontalAlignment align) {
            CellStyle st = wb.createCellStyle();
            st.setFont(font);
            st.setAlignment(align);
            return st;
        }
    }
}
