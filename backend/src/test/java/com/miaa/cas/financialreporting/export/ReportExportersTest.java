package com.miaa.cas.financialreporting.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ReportExportersTest {

    private static final ReportTable TABLE = new ReportTable("Title", List.of("Sub"), "Item", List.of("Text", "Amount"), List.of(
            new ReportTable.Row("=1+1", 0, false, List.of("@SUM(A1)", new BigDecimal("-1234.50"))),
            new ReportTable.Row("Plain \"quoted\"", 1, true, java.util.Arrays.asList(null, new BigDecimal("0.00")))), List.of("-note"));
    private static final Letterhead LETTERHEAD = new Letterhead("Avtech Information Technology Solutions, Inc.", "1 Sample St, Pasay 1300", null);

    @Test
    void csvNeutralisesFormulasAndQuotesText() {
        String csv = new String(ReportExporters.csv(TABLE, LETTERHEAD), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿\"Avtech Information Technology Solutions, Inc.\"\r\n\"1 Sample St, Pasay 1300\"\r\n");
        assertThat(csv).doesNotContain("TIN");
        assertThat(csv).contains("\"'=1+1\",\"'@SUM(A1)\",-1234.50");
        assertThat(csv).contains("\"  Plain \"\"quoted\"\"\",,0.00");
        assertThat(csv).contains("\"'-note\"");
        assertThat(ReportExporters.field("+cmd")).isEqualTo("\"'+cmd\"");
    }

    @Test
    void xlsxWritesTextAsTextAndAmountsAsNumbers() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(ReportExporters.xlsx(TABLE, LETTERHEAD)))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Avtech Information Technology Solutions, Inc.");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("1 Sample St, Pasay 1300");
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("Title");
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).isEqualTo("Item");
            var row = sheet.getRow(8);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("@SUM(A1)");
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(-1234.5);
        }
    }

    @Test
    void theLetterheadReplacesASubtitleRepeatingTheName() {
        ReportTable t = new ReportTable("Aging", List.of("Avtech Information Technology Solutions, Inc.", "As of 2026-11-06"), "Supplier",
                List.of("Total"), List.of(), List.of());
        String csv = new String(ReportExporters.csv(t, Letterhead.DEFAULT), StandardCharsets.UTF_8);
        assertThat(csv.split("Avtech Information Technology Solutions, Inc.", -1)).hasSize(2);
        assertThat(csv).contains("\"Aging\"\r\n\"As of 2026-11-06\"");
    }

    @Test
    void pdfIsAPdf() {
        assertThat(new String(ReportExporters.pdf(TABLE, LETTERHEAD), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
