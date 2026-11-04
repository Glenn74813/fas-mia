package com.miaa.cas.tax;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.tax.BirListingWorkbook.Sheet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The BIR lists of withholding tax and VAT:
 * <ul>
 * <li>the Quarterly Alphalist of Payees (QAP) attached to BIR Form 1601-EQ, and the annual alphalist of payees of BIR Form
 * 1604-E (Schedule 3), as DAT files in the file structures of RMC 7-2021 Annex A, from the same posted invoice lines as
 * BIR Form 2307;</li>
 * <li>the Summary Alphalist of Withholding Taxes (SAWT), from the certificates customers gave for tax withheld from
 * receipts;</li>
 * <li>the Summary Lists of Sales and of Purchases (SLSP), per customer and supplier.</li>
 * </ul>
 * BIR's DAT layouts for the SAWT and SLSP are not on its website, so those two are listings; their DAT files wait for
 * the layouts (clarification C-39). Every file generated is recorded in the audit trail.
 */
@Service
public class BirAlphalistService {

    /** A generated file. */
    public record Download(String fileName, byte[] content) {
    }

    /** A payee on the QAP or the annual alphalist: one row per payee, ATC and rate, with what the file still needs. */
    public record PayeeRow(int sequence, UUID supplierId, String supplierCode, String tin, String payeeType, String registeredName,
            String lastName, String firstName, String middleName, String atc, String nature, BigDecimal taxRate, BigDecimal incomePayment,
            BigDecimal taxWithheld, List<String> missing) {
    }

    /** The QAP of a quarter or the 1604-E alphalist of a year, ready to file when nothing is missing; no file name without the organisation's TIN. */
    public record Alphalist(String form, String period, String returnPeriod, String fileName, List<String> payorMissing,
            List<PayeeRow> rows, BigDecimal totalIncomePayment, BigDecimal totalTaxWithheld, boolean ready, String basis) {
    }

    /** A withholding agent (customer) and ATC on the SAWT. */
    public record SawtRow(int sequence, UUID customerId, String customerCode, String tin, String name, String atc, String nature,
            BigDecimal taxRate, BigDecimal incomePayment, BigDecimal taxWithheld, int receipts, List<String> missing) {
    }

    /** A posted receipt with tax withheld whose certificate has not been recorded, so it is not on the SAWT yet. */
    public record UncertifiedReceipt(UUID receiptId, String receiptNumber, LocalDate receiptDate, String customerName, BigDecimal taxWithheld) {
    }

    public record Sawt(int year, int quarter, List<SawtRow> rows, List<UncertifiedReceipt> uncertified, BigDecimal totalIncomePayment,
            BigDecimal totalTaxWithheld, String basis) {
    }

    /** A customer on the summary list of sales, or a supplier on the summary list of purchases. */
    public record VatRow(UUID partyId, String code, String tin, String name, String address, BigDecimal exempt, BigDecimal zeroRated,
            BigDecimal taxable, BigDecimal vat, BigDecimal gross, int documents, List<String> missing) {
    }

    public record VatListing(String list, int year, int quarter, List<VatRow> rows, BigDecimal exempt, BigDecimal zeroRated,
            BigDecimal taxable, BigDecimal vat, BigDecimal gross, String basis) {
    }

    private record Payor(String tin, String name, String rdoCode) {
    }

    private static final String ALPHALIST_BASIS = "Income payments are the posted supplier invoice lines with expanded withholding, "
            + "by accounting date, as on BIR Form 2307. File structure and name: RMC 7-2021 Annexes A and B. Validate the file with "
            + "BIR's Alphalist Data Entry and Validation Module before filing.";
    private static final String XLSX = "xlsx";

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public BirAlphalistService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ QAP (1601-EQ) and 1604-E

    @Transactional(readOnly = true)
    public Alphalist qap(int year, int quarter) {
        LocalDate from = start(year, quarter);
        LocalDate to = from.plusMonths(3).minusDays(1);
        return alphalist("1601EQ", "Q" + quarter + " " + year, String.format("%02d/%d", to.getMonthValue(), year),
                String.format("%02d%d", to.getMonthValue(), year), from, to);
    }

    @Transactional(readOnly = true)
    public Alphalist annual(int year) {
        start(year, 1);
        return alphalist("1604E", String.valueOf(year), "12/31/" + year, "1231" + year, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    @Transactional
    public Download qapDat(int year, int quarter) {
        Alphalist a = ready(qap(year, quarter));
        Payor p = payor();
        BirDat.Tin wa = BirDat.tin(p.tin());
        BirDat dat = new BirDat().record("HQAP", "H1601EQ", wa.number(), wa.branch(), BirDat.text(p.name(), 50), a.returnPeriod(), p.rdoCode());
        for (PayeeRow r : a.rows()) {
            BirDat.Tin t = BirDat.tin(r.tin());
            dat.record("D1", "1601EQ", r.sequence(), t.number(), t.branch(), names(r)[0], names(r)[1], names(r)[2], names(r)[3],
                    a.returnPeriod(), r.atc(), BirDat.rate(r.taxRate()), BirDat.amount(r.incomePayment()), BirDat.amount(r.taxWithheld()));
        }
        dat.record("C1", "1601EQ", wa.number(), wa.branch(), a.returnPeriod(), BirDat.amount(a.totalIncomePayment()),
                BirDat.amount(a.totalTaxWithheld()));
        return generated(a.fileName(), dat.bytes(), "QAP", a.period());
    }

    @Transactional
    public Download annualDat(int year) {
        Alphalist a = ready(annual(year));
        Payor p = payor();
        BirDat.Tin wa = BirDat.tin(p.tin());
        BirDat dat = new BirDat().record("H1604E", wa.number(), wa.branch(), a.returnPeriod());
        for (PayeeRow r : a.rows()) {
            BirDat.Tin t = BirDat.tin(r.tin());
            dat.record("D3", "1604E", wa.number(), wa.branch(), a.returnPeriod(), r.sequence(), t.number(), t.branch(), names(r)[0],
                    names(r)[1], names(r)[2], names(r)[3], r.atc(), BirDat.amount(r.incomePayment()), BirDat.rate(r.taxRate()),
                    BirDat.amount(r.taxWithheld()));
        }
        dat.record("C3", "1604E", wa.number(), wa.branch(), "12/" + year, BirDat.amount(a.totalTaxWithheld()));
        return generated(a.fileName(), dat.bytes(), "1604E", a.period());
    }

    @Transactional
    public Download alphalistWorkbook(Alphalist a) {
        List<List<Object>> rows = a.rows().stream().<List<Object>>map(r -> List.of(r.sequence(), nz(r.tin()), r.registeredName(),
                nz(r.lastName()), nz(r.firstName()), nz(r.middleName()), nz(r.atc()), nz(r.nature()), BirDat.rate(r.taxRate()),
                r.incomePayment(), r.taxWithheld(), String.join("; ", r.missing()))).toList();
        String title = "1601EQ".equals(a.form()) ? "QUARTERLY ALPHALIST OF PAYEES (BIR Form 1601-EQ) - " + a.period()
                : "ANNUAL ALPHALIST OF PAYEES SUBJECT TO EXPANDED WITHHOLDING TAX (BIR Form 1604-E, Schedule 3) - " + a.period();
        Sheet sheet = new Sheet(a.form(), heading(title), List.of("Seq", "TIN", "Registered name", "Last name", "First name", "Middle name",
                "ATC", "Nature of income payment", "Rate (%)", "Income payment", "Tax withheld", "Still needed"),
                new int[] {6, 18, 36, 18, 18, 18, 8, 50, 9, 16, 16, 40}, rows);
        return generated("BIR-" + a.form() + "-" + a.period().replace(" ", "-") + ".xlsx", BirListingWorkbook.render(List.of(sheet)),
                a.form() + "_" + XLSX, a.period());
    }

    private Alphalist alphalist(String form, String period, String returnPeriod, String filePeriod, LocalDate from, LocalDate to) {
        Payor p = payor();
        List<String> payorMissing = new ArrayList<>();
        if (BirDat.tin(p.tin()) == null) {
            payorMissing.add("organisation TIN");
        }
        if (p.name() == null) {
            payorMissing.add("organisation registered name");
        }
        if (p.rdoCode() == null) {
            payorMissing.add("organisation RDO code");
        }
        int[] seq = {0};
        List<PayeeRow> rows = jdbc.query("""
                SELECT s.id, s.code, s.tin, s.name, s.payee_type, s.last_name, s.first_name, s.middle_name, t.atc, t.rate,
                       a.payee_type AS atc_payee_type, a.description, sum(l.net_amount) AS base, sum(l.ewt_amount) AS tax
                FROM supplier_invoice_lines l JOIN supplier_invoices i ON i.id = l.invoice_id
                JOIN suppliers s ON s.id = i.supplier_id JOIN tax_codes t ON t.id = l.ewt_code_id
                LEFT JOIN bir_atc_codes a ON a.atc = t.atc
                WHERE i.status = 'POSTED' AND i.accounting_date BETWEEN ? AND ? AND l.ewt_amount > 0
                GROUP BY s.id, s.code, s.tin, s.name, s.payee_type, s.last_name, s.first_name, s.middle_name, t.atc, t.rate, a.payee_type,
                         a.description
                ORDER BY s.name, s.code, t.atc, t.rate""", (rs, i) -> {
                    String type = rs.getString("payee_type");
                    String atc = rs.getString("atc");
                    List<String> missing = payeeMissing(rs.getString("tin"), type, rs.getString("last_name"), rs.getString("first_name"),
                            atc, rs.getString("atc_payee_type"));
                    return new PayeeRow(++seq[0], rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("tin"), type,
                            "INDIVIDUAL".equals(type) ? "" : rs.getString("name"), rs.getString("last_name"), rs.getString("first_name"),
                            rs.getString("middle_name"), atc, rs.getString("description"), rs.getBigDecimal("rate"),
                            Money.of(rs.getBigDecimal("base")), Money.of(rs.getBigDecimal("tax")), missing);
                }, Date.valueOf(from), Date.valueOf(to));
        BigDecimal income = Money.of(rows.stream().map(PayeeRow::incomePayment).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal tax = Money.of(rows.stream().map(PayeeRow::taxWithheld).reduce(BigDecimal.ZERO, BigDecimal::add));
        BirDat.Tin wa = BirDat.tin(p.tin());
        String fileName = wa == null ? null : wa.number() + wa.branch() + filePeriod + form + ".DAT";
        boolean ready = !rows.isEmpty() && payorMissing.isEmpty() && rows.stream().allMatch(r -> r.missing().isEmpty());
        return new Alphalist(form, period, returnPeriod, fileName, payorMissing, rows, income, tax, ready, ALPHALIST_BASIS);
    }

    /** What a payee's row still needs for the file. WI ATCs are for individuals and WC ATCs for corporations. */
    static List<String> payeeMissing(String tin, String payeeType, String lastName, String firstName, String atc, String atcPayeeType) {
        List<String> missing = new ArrayList<>();
        if (BirDat.tin(tin) == null) {
            missing.add(tin == null ? "TIN" : "a TIN of nine digits and a branch code up to 9999");
        }
        if (payeeType == null) {
            missing.add("supplier type (individual or corporation)");
        } else if ("INDIVIDUAL".equals(payeeType) && (lastName == null || firstName == null)) {
            missing.add("last and first name (individual payee)");
        }
        if (atc == null) {
            missing.add("ATC of the tax code");
        } else if (payeeType != null && atcPayeeType != null && !payeeType.equals(atcPayeeType)) {
            missing.add("a tax code whose ATC is for " + ("INDIVIDUAL".equals(payeeType) ? "individuals" : "corporations"));
        }
        return missing;
    }

    /** Registered name for a corporation, or last, first and middle name for an individual, as name fields. */
    private static String[] names(PayeeRow r) {
        boolean individual = "INDIVIDUAL".equals(r.payeeType());
        return new String[] {BirDat.text(individual ? null : r.registeredName(), 50), BirDat.text(individual ? r.lastName() : null, 30),
            BirDat.text(individual ? r.firstName() : null, 30), BirDat.text(individual ? r.middleName() : null, 30)};
    }

    private static Alphalist ready(Alphalist a) {
        if (a.rows().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "There is no expanded withholding in " + a.period() + ".");
        }
        if (!a.ready()) {
            List<String> problems = new ArrayList<>(a.payorMissing());
            a.rows().stream().filter(r -> !r.missing().isEmpty())
                    .forEach(r -> problems.add(r.supplierCode() + ": " + String.join(", ", r.missing())));
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The " + a.form() + " file cannot be made until these are completed.",
                    Map.of("violations", problems));
        }
        return a;
    }

    // ------------------------------------------------------------------ SAWT

    @Transactional(readOnly = true)
    public Sawt sawt(int year, int quarter) {
        LocalDate from = start(year, quarter);
        LocalDate to = from.plusMonths(3).minusDays(1);
        int[] seq = {0};
        List<SawtRow> rows = jdbc.query("""
                SELECT c.id, c.code, c.tin, c.name, w.atc, a.description, sum(w.income_payment) AS income, sum(r.tax_withheld) AS tax,
                       count(*) AS receipts
                FROM receipts r JOIN customers c ON c.id = r.customer_id
                JOIN receipt_withholding_certificates w ON w.receipt_id = r.id JOIN bir_atc_codes a ON a.atc = w.atc
                WHERE r.status = 'POSTED' AND r.receipt_date BETWEEN ? AND ? AND r.tax_withheld > 0
                GROUP BY c.id, c.code, c.tin, c.name, w.atc, a.description ORDER BY c.name, c.code, w.atc""", (rs, i) -> {
                    BigDecimal income = Money.of(rs.getBigDecimal("income"));
                    BigDecimal tax = Money.of(rs.getBigDecimal("tax"));
                    return new SawtRow(++seq[0], rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("tin"), rs.getString("name"),
                            rs.getString("atc"), rs.getString("description"), tax.divide(income, 4, RoundingMode.HALF_UP), income, tax,
                            rs.getInt("receipts"), BirDat.tin(rs.getString("tin")) == null ? List.of("customer TIN") : List.of());
                }, Date.valueOf(from), Date.valueOf(to));
        List<UncertifiedReceipt> uncertified = jdbc.query("""
                SELECT r.id, r.receipt_number, r.receipt_date, c.name, r.tax_withheld FROM receipts r JOIN customers c ON c.id = r.customer_id
                WHERE r.status = 'POSTED' AND r.receipt_date BETWEEN ? AND ? AND r.tax_withheld > 0
                  AND NOT EXISTS (SELECT 1 FROM receipt_withholding_certificates w WHERE w.receipt_id = r.id)
                ORDER BY r.receipt_date, r.receipt_number""", (rs, i) -> new UncertifiedReceipt(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getDate(3).toLocalDate(), rs.getString(4), rs.getBigDecimal(5)), Date.valueOf(from), Date.valueOf(to));
        return new Sawt(year, quarter, rows, uncertified,
                Money.of(rows.stream().map(SawtRow::incomePayment).reduce(BigDecimal.ZERO, BigDecimal::add)),
                Money.of(rows.stream().map(SawtRow::taxWithheld).reduce(BigDecimal.ZERO, BigDecimal::add)),
                "Posted receipts dated in the quarter whose customer's BIR Form 2307 is recorded on the receipt. The rate is the tax "
                        + "withheld over the income payment. Receipts with tax withheld but no certificate are listed apart and left out.");
    }

    @Transactional
    public Download sawtWorkbook(int year, int quarter) {
        Sawt s = sawt(year, quarter);
        List<List<Object>> rows = s.rows().stream().<List<Object>>map(r -> List.of(r.sequence(), nz(r.tin()), r.name(), r.atc(), r.nature(),
                BirDat.rate(r.taxRate()), r.incomePayment(), r.taxWithheld(), r.receipts(), String.join("; ", r.missing()))).toList();
        List<Sheet> sheets = new ArrayList<>();
        sheets.add(new Sheet("SAWT", heading("SUMMARY ALPHALIST OF WITHHOLDING TAXES (SAWT) - Q" + quarter + " " + year),
                List.of("Seq", "TIN of withholding agent", "Withholding agent (customer)", "ATC", "Nature of income payment", "Rate (%)",
                        "Income payment", "Tax withheld", "Receipts", "Still needed"),
                new int[] {6, 20, 40, 8, 50, 9, 16, 16, 10, 24}, rows));
        if (!s.uncertified().isEmpty()) {
            sheets.add(new Sheet("Without certificate", List.of("Receipts with tax withheld but no BIR Form 2307 recorded (not on the SAWT)"),
                    List.of("Receipt", "Date", "Customer", "Tax withheld"), new int[] {22, 12, 40, 16},
                    s.uncertified().stream().<List<Object>>map(u -> List.of(u.receiptNumber(), u.receiptDate().toString(), u.customerName(),
                            u.taxWithheld())).toList()));
        }
        String period = "Q" + quarter + " " + year;
        return generated("BIR-SAWT-" + year + "Q" + quarter + ".xlsx", BirListingWorkbook.render(sheets), "SAWT_" + XLSX, period);
    }

    // ------------------------------------------------------------------ SLSP

    @Transactional(readOnly = true)
    public VatListing sales(int year, int quarter) {
        LocalDate from = start(year, quarter);
        return vat("SALES", year, quarter, """
                SELECT c.id, c.code, c.tin, c.name, c.address,
                       sum(CASE WHEN t.id IS NULL THEN k.sgn * d.net_amount ELSE 0 END) AS exempt,
                       sum(CASE WHEN t.rate = 0 THEN k.sgn * d.net_amount ELSE 0 END) AS zero_rated,
                       sum(CASE WHEN t.rate > 0 THEN k.sgn * d.net_amount ELSE 0 END) AS taxable,
                       sum(k.sgn * d.vat_amount) AS vat, count(*) AS documents
                FROM billing_documents d JOIN customers c ON c.id = d.customer_id LEFT JOIN tax_codes t ON t.id = d.vat_code_id
                CROSS JOIN LATERAL (SELECT CASE WHEN d.kind = 'CREDIT_MEMO' THEN -1 ELSE 1 END AS sgn) k
                WHERE d.status = 'POSTED' AND d.document_date BETWEEN ? AND ?
                GROUP BY c.id, c.code, c.tin, c.name, c.address ORDER BY c.name, c.code""", from,
                "Posted invoices and debit memos less credit memos, by document date, per customer. A document with no VAT code is "
                        + "counted as exempt and one with a 0% VAT code as zero-rated.");
    }

    @Transactional(readOnly = true)
    public VatListing purchases(int year, int quarter) {
        LocalDate from = start(year, quarter);
        return vat("PURCHASES", year, quarter, """
                SELECT s.id, s.code, s.tin, s.name, s.address,
                       sum(CASE WHEN t.id IS NULL THEN i.net_amount ELSE 0 END) AS exempt,
                       sum(CASE WHEN t.rate = 0 THEN i.net_amount ELSE 0 END) AS zero_rated,
                       sum(CASE WHEN t.rate > 0 THEN i.net_amount ELSE 0 END) AS taxable,
                       sum(i.vat_amount) AS vat, count(*) AS documents
                FROM supplier_invoices i JOIN suppliers s ON s.id = i.supplier_id LEFT JOIN tax_codes t ON t.id = i.vat_code_id
                WHERE i.status = 'POSTED' AND i.accounting_date BETWEEN ? AND ?
                GROUP BY s.id, s.code, s.tin, s.name, s.address ORDER BY s.name, s.code""", from,
                "Posted supplier invoices, by accounting date, per supplier. An invoice with no VAT code is counted as exempt or "
                        + "non-VAT and one with a 0% VAT code as zero-rated. The split of taxable purchases into services, capital goods "
                        + "and other goods is not recorded on invoices.");
    }

    @Transactional
    public Download vatWorkbook(VatListing v) {
        boolean sales = "SALES".equals(v.list());
        List<List<Object>> rows = v.rows().stream().<List<Object>>map(r -> List.of(nz(r.tin()), r.name(), nz(r.address()), r.exempt(),
                r.zeroRated(), r.taxable(), r.vat(), r.gross(), r.documents(), String.join("; ", r.missing()))).toList();
        String period = "Q" + v.quarter() + " " + v.year();
        Sheet sheet = new Sheet(sales ? "Sales" : "Purchases", heading("SUMMARY LIST OF " + v.list() + " - " + period),
                List.of("TIN", sales ? "Customer" : "Supplier", "Address", sales ? "Exempt sales" : "Exempt or non-VAT purchases",
                        sales ? "Zero-rated sales" : "Zero-rated purchases", sales ? "Taxable sales (net)" : "Taxable purchases (net)",
                        sales ? "Output VAT" : "Input VAT", "Gross amount", "Documents", "Still needed"),
                new int[] {20, 40, 40, 16, 16, 16, 16, 16, 10, 16}, rows);
        return generated("BIR-" + (sales ? "SLS" : "SLP") + "-" + v.year() + "Q" + v.quarter() + ".xlsx",
                BirListingWorkbook.render(List.of(sheet)), (sales ? "SLS_" : "SLP_") + XLSX, period);
    }

    private VatListing vat(String list, int year, int quarter, String sql, LocalDate from, String basis) {
        List<VatRow> rows = jdbc.query(sql, (rs, i) -> {
            BigDecimal exempt = Money.of(rs.getBigDecimal("exempt"));
            BigDecimal zero = Money.of(rs.getBigDecimal("zero_rated"));
            BigDecimal taxable = Money.of(rs.getBigDecimal("taxable"));
            BigDecimal vat = Money.of(rs.getBigDecimal("vat"));
            return new VatRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("tin"), rs.getString("name"),
                    rs.getString("address"), exempt, zero, taxable, vat, Money.of(exempt.add(zero).add(taxable).add(vat)), rs.getInt("documents"),
                    BirDat.tin(rs.getString("tin")) == null ? List.of("TIN") : List.of());
        }, Date.valueOf(from), Date.valueOf(from.plusMonths(3).minusDays(1)));
        return new VatListing(list, year, quarter, rows, sum(rows, VatRow::exempt), sum(rows, VatRow::zeroRated), sum(rows, VatRow::taxable),
                sum(rows, VatRow::vat), sum(rows, VatRow::gross), basis);
    }

    // ------------------------------------------------------------------ shared

    private Download generated(String fileName, byte[] content, String kind, String period) {
        audit.record("BIR_LIST_GENERATED", "BIR_LIST", kind + " " + period, Map.of("list", kind, "period", period, "file", fileName));
        return new Download(fileName, content);
    }

    private List<String> heading(String title) {
        Payor p = payor();
        return List.of(nz(p.name()), "TIN " + nz(p.tin()) + (p.rdoCode() == null ? "" : "   RDO " + p.rdoCode()), title);
    }

    private Payor payor() {
        return jdbc.query("SELECT tin, name, rdo_code FROM organizations WHERE status = 'ACTIVE' ORDER BY code LIMIT 1",
                (rs, i) -> new Payor(rs.getString(1), rs.getString(2), rs.getString(3))).stream().findFirst().orElse(new Payor(null, null, null));
    }

    private static BigDecimal sum(List<VatRow> rows, java.util.function.Function<VatRow, BigDecimal> f) {
        return Money.of(rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static LocalDate start(int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "quarter must be 1-4");
        }
        if (year < 2000 || year > 9999) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "year must be 2000-9999");
        }
        return LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
    }
}
