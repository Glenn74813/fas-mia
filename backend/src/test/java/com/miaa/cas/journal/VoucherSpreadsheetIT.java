package com.miaa.cas.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class VoucherSpreadsheetIT extends AbstractAccountingIT {

    @Test
    void exportsTheDateRangeAndReimportsItAsDrafts() throws Exception {
        int year = newOpenYear();
        createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 2, 10), utilitiesLines("750.25")));
        createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 4, 1), utilitiesLines("10.00")));

        byte[] file = mvc.perform(get("/api/v1/journal-vouchers/export").param("from", year + "-02-01").param("to", year + "-02-28")
                .header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet lines = wb.getSheet("Lines");
            assertThat(lines.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Voucher reference");
            assertThat(lines.getLastRowNum()).isEqualTo(2); // the April voucher is outside the range
            assertThat(lines.getRow(1).getCell(5).getStringCellValue()).isEqualTo("PLC-5201");
            assertThat(lines.getRow(1).getCell(9).getNumericCellValue()).isEqualTo(750.25);
            assertThat(lines.getRow(2).getCell(10).getNumericCellValue()).isEqualTo(750.25);
        }

        JsonNode result = body(mvc.perform(multipart("/api/v1/journal-vouchers/import")
                .file(new MockMultipartFile("file", "vouchers.xlsx", "application/octet-stream", file))
                .header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(result.get("vouchers").asInt()).isEqualTo(1);
        assertThat(result.get("lines").asInt()).isEqualTo(2);
        JsonNode copy = body(mvc.perform(get("/api/v1/journal-vouchers/" + result.at("/voucherIds/0").asText())
                .header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(copy.get("status").asText()).isEqualTo("DRAFT");
        assertThat(copy.get("totalDebit").asText()).isEqualTo("750.25");
        assertThat(copy.get("preparedBy").asText()).isEqualTo(preparerUser.username());

        mvc.perform(get("/api/v1/journal-vouchers/export").param("from", year + "-03-01").param("to", year + "-02-01")
                .header("Authorization", preparer.bearer())).andExpect(status().isBadRequest());
    }

    @Test
    void aFileWithProblemsImportsNothingAndListsEveryRow() throws Exception {
        int year = newOpenYear();
        byte[] file;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Lines");
            String[][] rows = {
                VoucherSpreadsheet.COLUMNS.toArray(String[]::new),
                {"A", "STANDARD", year + "-05-02", "Good voucher", "", "PLC-5201", "PLC-CC-FIN", "", "", "100", ""},
                {"A", "STANDARD", year + "-05-02", "Good voucher", "", "PLC-1102", "", "", "", "", "100"},
                {"B", "STANDARD", year + "-05-03", "Bad voucher", "", "NO-SUCH", "", "", "", "50", ""},
                {"B", "STANDARD", year + "-05-03", "Bad voucher", "", "PLC-1102", "", "", "", "", "40"}};
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (!rows[r][c].isEmpty()) {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            wb.write(out);
            file = out.toByteArray();
        }
        long before = jdbc.queryForObject("SELECT count(*) FROM journal_vouchers", Long.class);
        mvc.perform(multipart("/api/v1/journal-vouchers/import")
                .file(new MockMultipartFile("file", "bad.xlsx", "application/octet-stream", file))
                .header("Authorization", preparer.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations", Matchers.hasItem(Matchers.containsString("Row 4: no account has the code NO-SUCH"))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_vouchers", Long.class)).isEqualTo(before);

        mvc.perform(multipart("/api/v1/journal-vouchers/import")
                .file(new MockMultipartFile("file", "junk.xlsx", "application/octet-stream", "not a workbook".getBytes()))
                .header("Authorization", preparer.bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }
}
