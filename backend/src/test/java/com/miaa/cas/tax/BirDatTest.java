package com.miaa.cas.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BirDatTest {

    @Test
    void splitsTinIntoNineDigitsAndFourDigitBranch() {
        assertThat(BirDat.tin("123-456-789")).isEqualTo(new BirDat.Tin("123456789", "0000"));
        assertThat(BirDat.tin("123-456-789-00000")).isEqualTo(new BirDat.Tin("123456789", "0000"));
        assertThat(BirDat.tin("123456789002")).isEqualTo(new BirDat.Tin("123456789", "0002"));
        assertThat(BirDat.tin("123-456-78")).isNull();
        assertThat(BirDat.tin("123-456-789-12345")).isNull();
        assertThat(BirDat.tin(null)).isNull();
    }

    @Test
    void writesNamesQuotedAndCutToWidth() {
        assertThat(BirDat.text("Niño \"Pepe\" Peña", 50)).isEqualTo("\"Nino Pepe Pena\"");
        assertThat(BirDat.text("ABCDEFGHIJ", 5)).isEqualTo("\"ABCDE\"");
        assertThat(BirDat.text(null, 30)).isEqualTo("\"\"");
    }

    @Test
    void writesAmountsAndRates() {
        assertThat(BirDat.amount(new BigDecimal("1234567.5"))).isEqualTo("1234567.50");
        assertThat(BirDat.rate(new BigDecimal("0.0100"))).isEqualTo("1.00");
        assertThat(BirDat.rate(new BigDecimal("0.15"))).isEqualTo("15.00");
    }

    @Test
    void endsLinesWithCrLf() {
        byte[] b = new BirDat().record("HQAP", "H1601EQ").record("C1", null, "1.00").bytes();
        assertThat(new String(b, StandardCharsets.US_ASCII)).isEqualTo("HQAP,H1601EQ\r\nC1,,1.00\r\n");
    }
}
