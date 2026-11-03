package com.miaa.cas.accountspayable;

import com.miaa.cas.accountspayable.Form2307Pdf.Certificate;
import com.miaa.cas.accountspayable.Form2307Pdf.Line;
import com.miaa.cas.accountspayable.Form2307Pdf.Party;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BIR Form 2307 (certificate of creditable tax withheld at source) for a supplier and quarter, from the same posted invoice
 * lines as the expanded withholding schedule. A certificate missing a detail the form requires is still printed so it can be
 * checked, but as a watermarked draft. The signature blocks stay blank, to be signed on paper. Every certificate generated is
 * recorded in the audit trail.
 */
@Service
public class Form2307Service {

    /** A generated file. */
    public record Download(String fileName, byte[] content) {
    }

    private record Supplier(UUID id, String code, String payeeType, Party party) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public Form2307Service(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * What a supplier's certificate still needs for one line. The ATC belongs to the tax code of the line, and WI codes are for
     * individuals and WC codes for corporations, so a code of the other kind is reported as well as a missing one.
     */
    static List<String> missing(String tin, String address, String zipCode, String payeeType, String atc, String atcPayeeType) {
        List<String> missing = new ArrayList<>();
        if (tin == null) {
            missing.add("supplier TIN");
        }
        if (address == null) {
            missing.add("supplier address");
        }
        if (zipCode == null) {
            missing.add("supplier ZIP code");
        }
        if (payeeType == null) {
            missing.add("supplier type (individual or corporation)");
        }
        if (atc == null) {
            missing.add("ATC of the tax code");
        } else if (payeeType != null && atcPayeeType != null && !payeeType.equals(atcPayeeType)) {
            missing.add("a tax code whose ATC is for " + ("INDIVIDUAL".equals(payeeType) ? "individuals" : "corporations") + " (" + atc + " is not)");
        }
        return missing;
    }

    /** What the organisation's own part of the form still needs. */
    @Transactional(readOnly = true)
    public List<String> payorMissing() {
        Party p = payor();
        List<String> missing = new ArrayList<>();
        if (p.tin() == null) {
            missing.add("organisation TIN");
        }
        if (p.address() == null) {
            missing.add("organisation address");
        }
        if (p.zipCode() == null) {
            missing.add("organisation ZIP code");
        }
        return missing;
    }

    @Transactional
    public Download supplier(UUID id, int year, int quarter) {
        LocalDate from = start(year, quarter);
        Download d = certificate(load(id), from, year, quarter);
        if (d == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "This supplier has no expanded withholding in Q" + quarter + " " + year + ".");
        }
        return d;
    }

    /** Every supplier with withholding in the quarter, as one ZIP. */
    @Transactional
    public Download quarter(int year, int quarter) {
        LocalDate from = start(year, quarter);
        List<UUID> ids = jdbc.queryForList("""
                SELECT DISTINCT i.supplier_id FROM supplier_invoice_lines l JOIN supplier_invoices i ON i.id = l.invoice_id
                WHERE i.status = 'POSTED' AND i.accounting_date BETWEEN ? AND ? AND l.ewt_amount > 0""", UUID.class,
                Date.valueOf(from), Date.valueOf(from.plusMonths(3).minusDays(1)));
        if (ids.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "There is no expanded withholding in Q" + quarter + " " + year + ".");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (UUID id : ids) {
                Download d = certificate(load(id), from, year, quarter);
                zip.putNextEntry(new ZipEntry(d.fileName()));
                zip.write(d.content());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Download("BIR2307-" + year + "Q" + quarter + ".zip", out.toByteArray());
    }

    private Download certificate(Supplier s, LocalDate from, int year, int quarter) {
        LocalDate to = from.plusMonths(3).minusDays(1);
        int m1 = from.getMonthValue();
        List<String> lineProblems = new ArrayList<>();
        List<Line> lines = jdbc.query("""
                SELECT t.description, t.atc, a.payee_type AS atc_payee_type,
                       sum(CASE WHEN extract(month FROM i.accounting_date) = ? THEN l.net_amount ELSE 0 END) AS m1,
                       sum(CASE WHEN extract(month FROM i.accounting_date) = ? THEN l.net_amount ELSE 0 END) AS m2,
                       sum(CASE WHEN extract(month FROM i.accounting_date) = ? THEN l.net_amount ELSE 0 END) AS m3,
                       sum(l.net_amount) AS base, sum(l.ewt_amount) AS tax
                FROM supplier_invoice_lines l JOIN supplier_invoices i ON i.id = l.invoice_id JOIN tax_codes t ON t.id = l.ewt_code_id
                LEFT JOIN bir_atc_codes a ON a.atc = t.atc
                WHERE i.supplier_id = ? AND i.status = 'POSTED' AND i.accounting_date BETWEEN ? AND ? AND l.ewt_amount > 0
                GROUP BY t.code, t.description, t.atc, a.payee_type ORDER BY t.code""", (rs, i) -> {
                    lineProblems.addAll(missing(s.party().tin(), s.party().address(), s.party().zipCode(), s.payeeType(), rs.getString("atc"),
                            rs.getString("atc_payee_type")));
                    return new Line(rs.getString("description"), rs.getString("atc"),
                rs.getBigDecimal("m1"), rs.getBigDecimal("m2"), rs.getBigDecimal("m3"), rs.getBigDecimal("base"), rs.getBigDecimal("tax"));
                }, m1, m1 + 1, m1 + 2, s.id(), Date.valueOf(from), Date.valueOf(to));
        if (lines.isEmpty()) {
            return null;
        }
        Party payor = payor();
        boolean draft = !payorMissing().isEmpty() || !lineProblems.isEmpty();
        byte[] pdf = Form2307Pdf.render(new Certificate(from, to, s.party(), payor, lines, draft));
        audit.record("BIR_2307_GENERATED", "SUPPLIER", s.code(), Map.of("year", year, "quarter", quarter, "draft", draft));
        return new Download("BIR2307-" + s.code() + "-" + year + "Q" + quarter + (draft ? "-DRAFT" : "") + ".pdf", pdf);
    }

    private Supplier load(UUID id) {
        return jdbc.query("SELECT id, code, payee_type, tin, name, address, zip_code, foreign_address FROM suppliers WHERE id = ?", (rs, i) -> new Supplier(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("payee_type"), new Party(rs.getString("tin"), rs.getString("name"),
                rs.getString("address"), rs.getString("zip_code"), rs.getString("foreign_address"))), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Supplier"));
    }

    private Party payor() {
        return jdbc.query("SELECT tin, name, address, zip_code FROM organizations WHERE status = 'ACTIVE' ORDER BY code LIMIT 1", (rs, i) ->
                new Party(rs.getString("tin"), rs.getString("name"), rs.getString("address"), rs.getString("zip_code"), null))
                .stream().findFirst().orElse(new Party(null, null, null, null, null));
    }

    private static LocalDate start(int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "quarter must be 1-4");
        }
        return LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
    }
}
