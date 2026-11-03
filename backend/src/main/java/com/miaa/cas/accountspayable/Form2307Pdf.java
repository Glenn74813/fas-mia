package com.miaa.cas.accountspayable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfCopy;
import org.openpdf.text.pdf.PdfGState;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfStamper;

/**
 * Prints a certificate on the official BIR Form 2307 (January 2018 ENCS). The form has no fillable fields, so the values
 * are placed by position; the coordinates below are measured from the form, in points from the top left of its page.
 * A supplier with more lines than a page holds continues on further page 1 sheets, each with its own totals.
 */
final class Form2307Pdf {

    record Party(String tin, String name, String address, String zipCode, String foreignAddress) {
    }

    record Line(String description, String atc, BigDecimal month1, BigDecimal month2, BigDecimal month3, BigDecimal total, BigDecimal tax) {

        /** Money payments subject to withholding of business tax use WB and WV codes; everything else is expanded withholding. */
        boolean moneyPayment() {
            return atc != null && (atc.startsWith("WB") || atc.startsWith("WV"));
        }
    }

    record Certificate(LocalDate from, LocalDate to, Party payee, Party payor, List<Line> lines, boolean draft) {
    }

    private static final String TEMPLATE = "/forms/bir-2307-jan-2018.pdf";
    private static final float PAGE_HEIGHT = 936f;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMddyyyy");

    /** Digit boxes as {left, right, number of digits}. */
    private static final float[][] FROM = {{151.5f, 177.5f, 2}, {177.5f, 204f, 2}, {204f, 256.5f, 4}};
    private static final float[][] TO = {{399f, 425f, 2}, {425f, 451.5f, 2}, {451.5f, 504f, 4}};
    private static final float[][] PAYEE_TIN = {{207f, 246.5f, 3}, {258.5f, 297.5f, 3}, {310f, 349f, 3}, {361.5f, 435f, 5}};
    private static final float[][] PAYOR_TIN = {{208f, 247.5f, 3}, {259.5f, 298.5f, 3}, {310.5f, 350f, 3}, {362f, 436f, 5}};
    private static final float[][] ZIP = {{541.5f, 591.5f, 4}};

    private static final float TEXT_LEFT = 37f;
    private static final float ADDRESS_WIDTH = 495f;
    private static final float WIDE_WIDTH = 555f;

    /** Tops of the ten rows of each section, and of its total row. */
    private static final float[] INCOME_ROWS = {365f, 378.5f, 392f, 406f, 419.5f, 433.5f, 447f, 460.5f, 474.5f, 488f};
    private static final float INCOME_TOTAL = 501.5f;
    private static final float[] MONEY_ROWS = {534.5f, 548f, 561.5f, 575.5f, 589f, 602.5f, 616.5f, 630f, 644f, 657.5f};
    private static final float MONEY_TOTAL = 671f;
    private static final float DESCRIPTION_LEFT = 19.5f;
    private static final float DESCRIPTION_WIDTH = 155f;
    private static final float ATC_CENTRE = 198f;
    /** Right edges of month 1, month 2, month 3, total and tax withheld. */
    private static final float[] AMOUNT_RIGHT = {288.5f, 363f, 435f, 507f, 593f};

    private Form2307Pdf() {
    }

    static byte[] render(Certificate c) {
        try {
            BaseFont font = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            List<Line> income = c.lines().stream().filter(l -> !l.moneyPayment()).toList();
            List<Line> money = c.lines().stream().filter(Line::moneyPayment).toList();
            int sheets = Math.max(1, Math.max(sheetsFor(income.size(), INCOME_ROWS.length), sheetsFor(money.size(), MONEY_ROWS.length)));
            List<byte[]> pages = new ArrayList<>();
            for (int i = 0; i < sheets; i++) {
                pages.add(sheet(c, font, page(income, i, INCOME_ROWS.length), page(money, i, MONEY_ROWS.length), i + 1, sheets));
            }
            return sheets == 1 ? pages.get(0) : join(pages);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not print BIR Form 2307", e);
        }
    }

    private static int sheetsFor(int lines, int perSheet) {
        return (lines + perSheet - 1) / perSheet;
    }

    private static List<Line> page(List<Line> lines, int sheet, int perSheet) {
        int from = Math.min(sheet * perSheet, lines.size());
        return lines.subList(from, Math.min(from + perSheet, lines.size()));
    }

    private static byte[] template() throws IOException {
        try (InputStream in = Form2307Pdf.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IOException("Missing form template " + TEMPLATE);
            }
            return in.readAllBytes();
        }
    }

    private static byte[] sheet(Certificate c, BaseFont font, List<Line> income, List<Line> money, int sheet, int sheets)
            throws IOException, DocumentException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfReader reader = new PdfReader(template());
        PdfStamper stamper = new PdfStamper(reader, out);
        PdfContentByte cb = stamper.getOverContent(1);
        cb.beginText();
        boxes(cb, font, DATE.format(c.from()), FROM, 117f);
        boxes(cb, font, DATE.format(c.to()), TO, 117f);
        boxes(cb, font, digits(c.payee().tin()), PAYEE_TIN, 148f);
        fit(cb, font, c.payee().name(), TEXT_LEFT, 175f, WIDE_WIDTH);
        fit(cb, font, c.payee().address(), TEXT_LEFT, 204f, ADDRESS_WIDTH);
        boxes(cb, font, c.payee().zipCode(), ZIP, 204f);
        fit(cb, font, c.payee().foreignAddress(), TEXT_LEFT, 232f, WIDE_WIDTH);
        boxes(cb, font, digits(c.payor().tin()), PAYOR_TIN, 263.5f);
        fit(cb, font, c.payor().name(), TEXT_LEFT, 290f, WIDE_WIDTH);
        fit(cb, font, c.payor().address(), TEXT_LEFT, 319f, ADDRESS_WIDTH);
        boxes(cb, font, c.payor().zipCode(), ZIP, 319f);
        section(cb, font, income, INCOME_ROWS, INCOME_TOTAL);
        section(cb, font, money, MONEY_ROWS, MONEY_TOTAL);
        if (sheets > 1) {
            text(cb, font, 7f, "Sheet " + sheet + " of " + sheets, 593f, 98f, Element.ALIGN_RIGHT);
        }
        cb.endText();
        if (c.draft()) {
            watermark(stamper.getOverContent(1), font);
        }
        stamper.close();
        reader.close();
        return out.toByteArray();
    }

    private static void section(PdfContentByte cb, BaseFont font, List<Line> lines, float[] rows, float total) {
        BigDecimal[] sums = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            description(cb, font, l.description(), rows[i]);
            if (l.atc() != null) {
                text(cb, font, 7.5f, l.atc(), ATC_CENTRE, rows[i] + 9.7f, Element.ALIGN_CENTER);
            }
            BigDecimal[] values = {l.month1(), l.month2(), l.month3(), l.total(), l.tax()};
            for (int v = 0; v < values.length; v++) {
                sums[v] = sums[v].add(values[v]);
                if (values[v].signum() != 0) {
                    amount(cb, font, values[v], AMOUNT_RIGHT[v], rows[i] + 9.7f);
                }
            }
        }
        if (!lines.isEmpty()) {
            for (int v = 0; v < sums.length; v++) {
                amount(cb, font, sums[v], AMOUNT_RIGHT[v], total + 9.7f);
            }
        }
    }

    private static void amount(PdfContentByte cb, BaseFont font, BigDecimal value, float right, float baseline) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        text(cb, font, 7.5f, format.format(value), right, baseline, Element.ALIGN_RIGHT);
    }

    /** One line if it fits at the normal size, otherwise two smaller lines, cut if it is longer still. */
    private static void description(PdfContentByte cb, BaseFont font, String value, float rowTop) {
        if (font.getWidthPoint(value, 7f) <= DESCRIPTION_WIDTH) {
            text(cb, font, 7f, value, DESCRIPTION_LEFT, rowTop + 9.7f, Element.ALIGN_LEFT);
            return;
        }
        float size = 5.5f;
        int split = value.length();
        while (split > 0 && font.getWidthPoint(value.substring(0, split), size) > DESCRIPTION_WIDTH) {
            split = value.lastIndexOf(' ', split - 1);
        }
        if (split <= 0) {
            split = cut(font, value, size, DESCRIPTION_WIDTH).length();
        }
        text(cb, font, size, value.substring(0, split), DESCRIPTION_LEFT, rowTop + 5.8f, Element.ALIGN_LEFT);
        String rest = cut(font, value.substring(split).trim(), size, DESCRIPTION_WIDTH);
        text(cb, font, size, rest, DESCRIPTION_LEFT, rowTop + 11.8f, Element.ALIGN_LEFT);
    }

    /** Shrinks a value to fit its box, down to a size that stays legible, then cuts it. */
    private static void fit(PdfContentByte cb, BaseFont font, String value, float left, float baseline, float width) {
        if (value == null) {
            return;
        }
        int quarterPoints = 36;
        while (quarterPoints > 24 && font.getWidthPoint(value, quarterPoints / 4f) > width) {
            quarterPoints--;
        }
        float size = quarterPoints / 4f;
        text(cb, font, size, cut(font, value, size, width), left, baseline, Element.ALIGN_LEFT);
    }

    private static String cut(BaseFont font, String value, float size, float width) {
        String v = value;
        while (!v.isEmpty() && font.getWidthPoint(v, size) > width) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /** One digit centred in each box; a shorter value leaves the last boxes empty (a TIN without its branch code). */
    private static void boxes(PdfContentByte cb, BaseFont font, String value, float[][] groups, float baseline) {
        if (value == null) {
            return;
        }
        int at = 0;
        for (float[] g : groups) {
            int count = (int) g[2];
            float cell = (g[1] - g[0]) / count;
            for (int i = 0; i < count && at < value.length(); i++, at++) {
                text(cb, font, 9f, value.substring(at, at + 1), g[0] + cell * (i + 0.5f), baseline, Element.ALIGN_CENTER);
            }
        }
    }

    private static String digits(String tin) {
        return tin == null ? null : tin.replaceAll("\\D", "");
    }

    private static void text(PdfContentByte cb, BaseFont font, float size, String value, float x, float fromTop, int align) {
        cb.setFontAndSize(font, size);
        cb.showTextAligned(align, value, x, PAGE_HEIGHT - fromTop, 0);
    }

    private static void watermark(PdfContentByte cb, BaseFont font) {
        PdfGState faint = new PdfGState();
        faint.setFillOpacity(0.22f);
        cb.saveState();
        cb.setGState(faint);
        cb.setRGBColorFill(200, 30, 30);
        cb.beginText();
        cb.setFontAndSize(font, 90f);
        cb.showTextAligned(Element.ALIGN_CENTER, "DRAFT", 306f, 480f, 50f);
        cb.setFontAndSize(font, 20f);
        cb.showTextAligned(Element.ALIGN_CENTER, "Incomplete details: not a valid certificate", 340f, 440f, 50f);
        cb.endText();
        cb.restoreState();
    }

    private static byte[] join(List<byte[]> sheets) throws IOException, DocumentException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document();
        PdfCopy copy = new PdfCopy(document, out);
        document.open();
        for (byte[] sheet : sheets) {
            PdfReader reader = new PdfReader(sheet);
            copy.addPage(copy.getImportedPage(reader, 1));
            copy.freeReader(reader);
            reader.close();
        }
        // The schedule of ATCs on page 2 is the same for every sheet.
        PdfReader schedule = new PdfReader(sheets.get(0));
        copy.addPage(copy.getImportedPage(schedule, 2));
        copy.freeReader(schedule);
        schedule.close();
        document.close();
        return out.toByteArray();
    }
}
