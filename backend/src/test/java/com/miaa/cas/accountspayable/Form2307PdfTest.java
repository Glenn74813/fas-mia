package com.miaa.cas.accountspayable;

import static org.assertj.core.api.Assertions.assertThat;

import com.miaa.cas.accountspayable.Form2307Pdf.Certificate;
import com.miaa.cas.accountspayable.Form2307Pdf.Line;
import com.miaa.cas.accountspayable.Form2307Pdf.Party;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

class Form2307PdfTest {

    private static final Party PAYEE = new Party("123-456-789-00000", "Acme Supply Corporation", "1 Rizal Avenue, Pasay City", "1300", "Foreign St");
    private static final Party PAYOR = new Party("987-654-321", "Avtech Information Technology Solutions, Inc.", "2 Ayala Avenue, Makati City", "1226", null);

    private static Line line(String description, String atc) {
        return new Line(description, atc, new BigDecimal("1000.00"), BigDecimal.ZERO, new BigDecimal("500.50"), new BigDecimal("1500.50"),
                new BigDecimal("30.01"));
    }

    private static Certificate certificate(List<Line> lines, boolean draft) {
        return new Certificate(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), PAYEE, PAYOR, lines, draft);
    }

    private static String page(byte[] pdf, int page) throws IOException {
        try (PdfReader reader = new PdfReader(pdf)) {
            return new PdfTextExtractor(reader).getTextFromPage(page);
        }
    }

    private static int pages(byte[] pdf) throws IOException {
        try (PdfReader reader = new PdfReader(pdf)) {
            return reader.getNumberOfPages();
        }
    }

    @Test
    void printsTheCertificateOnTheOfficialForm() throws IOException {
        byte[] pdf = Form2307Pdf.render(certificate(List.of(line("Services", "WC158"), line("Business tax", "WB080")), false));
        String text = page(pdf, 1);
        assertThat(pages(pdf)).isEqualTo(2);
        assertThat(text).contains("Certificate of Creditable Tax", "Acme Supply Corporation", "1 Rizal Avenue", "Avtech Information Technology",
                "WC158", "WB080", "1,500.50", "30.01").doesNotContain("DRAFT");
    }

    @Test
    void marksAnIncompleteCertificateAsADraft() throws IOException {
        assertThat(page(Form2307Pdf.render(certificate(List.of(line("Services", null)), true)), 1)).contains("DRAFT", "not a valid certificate");
    }

    @Test
    void continuesOnAnotherSheetWhenThereAreMoreLinesThanRows() throws IOException {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            lines.add(line("Service " + i, "WC" + (100 + i)));
        }
        byte[] pdf = Form2307Pdf.render(certificate(lines, false));
        assertThat(pages(pdf)).isEqualTo(3);
        assertThat(page(pdf, 1)).contains("WC109", "Sheet 1 of 2").doesNotContain("WC110");
        assertThat(page(pdf, 2)).contains("WC110", "WC111", "Sheet 2 of 2");
        assertThat(page(pdf, 3)).contains("SCHEDULES OF ALPHANUMERIC TAX CODES");
    }

    @Test
    void cutsTextThatCannotFitRatherThanFailing() throws IOException {
        String longText = "A very long income payment description ".repeat(10);
        assertThat(pages(Form2307Pdf.render(certificate(List.of(line(longText, "WC158")), false)))).isEqualTo(2);
    }
}
