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

    @Test
    void csvNeutralisesFormulasAndQuotesText() {
        String csv = new String(ReportExporters.csv(TABLE), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿");
        assertThat(csv).contains("\"'=1+1\",\"'@SUM(A1)\",-1234.50");
        assertThat(csv).contains("\"  Plain \"\"quoted\"\"\",,0.00");
        assertThat(csv).contains("\"'-note\"");
        assertThat(ReportExporters.field("+cmd")).isEqualTo("\"'+cmd\"");
    }

    @Test
    void xlsxWritesTextAsTextAndAmountsAsNumbers() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(ReportExporters.xlsx(TABLE)))) {
            var row = wb.getSheetAt(0).getRow(4);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("@SUM(A1)");
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(-1234.5);
        }
    }

    @Test
    void pdfIsAPdf() {
        assertThat(new String(ReportExporters.pdf(TABLE), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
