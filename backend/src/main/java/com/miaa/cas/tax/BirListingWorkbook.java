package com.miaa.cas.tax;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * A BIR listing as an Excel workbook: one sheet per list, each with the taxpayer's heading, the columns, a row per entry
 * and a total of every amount column. Amount columns are those whose values are {@link BigDecimal}.
 */
final class BirListingWorkbook {

    /** One sheet: its name, the title and heading lines above the table, the columns, their widths and the rows. */
    record Sheet(String name, List<String> heading, List<String> columns, int[] widths, List<List<Object>> rows) {
    }

    private BirListingWorkbook() {
    }

    static byte[] render(List<Sheet> sheets) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = wb.createCellStyle();
            XSSFFont b = wb.createFont();
            b.setBold(true);
            bold.setFont(b);
            CellStyle head = wb.createCellStyle();
            head.cloneStyleFrom(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            head.setWrapText(true);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle total = wb.createCellStyle();
            total.cloneStyleFrom(money);
            total.setFont(b);
            total.setBorderTop(BorderStyle.THIN);
            for (Sheet s : sheets) {
                XSSFSheet sh = wb.createSheet(s.name());
                for (int c = 0; c < s.widths().length; c++) {
                    sh.setColumnWidth(c, s.widths()[c] * 256);
                }
                int r = 0;
                for (String line : s.heading()) {
                    Cell cell = sh.createRow(r++).createCell(0);
                    cell.setCellValue(line);
                    cell.setCellStyle(bold);
                }
                r++;
                int headerRow = r;
                Row h = sh.createRow(r++);
                for (int c = 0; c < s.columns().size(); c++) {
                    Cell cell = h.createCell(c);
                    cell.setCellValue(s.columns().get(c));
                    cell.setCellStyle(head);
                }
                BigDecimal[] totals = new BigDecimal[s.columns().size()];
                for (List<Object> values : s.rows()) {
                    Row row = sh.createRow(r++);
                    for (int c = 0; c < values.size(); c++) {
                        Object v = values.get(c);
                        Cell cell = row.createCell(c);
                        if (v instanceof BigDecimal d) {
                            cell.setCellValue(d.doubleValue());
                            cell.setCellStyle(money);
                            totals[c] = totals[c] == null ? d : totals[c].add(d);
                        } else if (v instanceof Number n) {
                            cell.setCellValue(n.doubleValue());
                        } else if (v != null) {
                            cell.setCellValue(v.toString());
                        }
                    }
                }
                Row t = sh.createRow(r);
                Cell label = t.createCell(0);
                label.setCellValue("Total (" + s.rows().size() + (s.rows().size() == 1 ? " entry)" : " entries)"));
                label.setCellStyle(bold);
                for (int c = 1; c < totals.length; c++) {
                    if (totals[c] != null) {
                        Cell cell = t.createCell(c);
                        cell.setCellValue(totals[c].doubleValue());
                        cell.setCellStyle(total);
                    }
                }
                sh.createFreezePane(0, headerRow + 1);
                sh.setRepeatingRows(new CellRangeAddress(headerRow, headerRow, -1, -1));
                sh.getPrintSetup().setLandscape(true);
                sh.getPrintSetup().setPaperSize(PrintSetup.LEGAL_PAPERSIZE);
                sh.setFitToPage(true);
                sh.getPrintSetup().setFitWidth((short) 1);
                sh.getPrintSetup().setFitHeight((short) 0);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
