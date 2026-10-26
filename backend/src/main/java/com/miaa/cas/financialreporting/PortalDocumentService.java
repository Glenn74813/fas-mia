package com.miaa.cas.financialreporting;

import com.miaa.cas.accountsreceivable.ArDtos.BillingLineView;
import com.miaa.cas.accountsreceivable.ArDtos.PortalAccount;
import com.miaa.cas.accountsreceivable.ArDtos.Statement;
import com.miaa.cas.accountsreceivable.ArDtos.StatementLine;
import com.miaa.cas.accountsreceivable.PortalService;
import com.miaa.cas.accountsreceivable.PortalService.PortalBilling;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.financialreporting.export.ReportExporters;
import com.miaa.cas.financialreporting.export.ReportTable;
import com.miaa.cas.security.CurrentActor;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PDF copies a concessionaire downloads from the portal (MS §11.3): a posted billing and the statement of account. They
 * are built from the portal's own views, which resolve the caller's customer and refuse anyone else's records. They
 * are copies for reference, not BIR-registered invoices or official receipts (C-06, C-29). Every download is audited.
 */
@Service
public class PortalDocumentService {

    public record PortalPdf(String fileName, byte[] content) {
    }

    private static final String ORG = "Manila International Airport Authority";
    private static final String NOT_OFFICIAL = "Copy for the concessionaire's reference. It is not an official invoice or receipt: "
            + "the BIR-registered format and series are pending (C-06, C-29).";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'PHT'").withZone(ZoneId.of("Asia/Manila"));

    private final PortalService portal;
    private final AuditService audit;
    private final Clock clock;

    public PortalDocumentService(PortalService portal, AuditService audit, Clock clock) {
        this.portal = portal;
        this.audit = audit;
        this.clock = clock;
    }

    // Not read-only: each download is written to the audit trail.
    @Transactional
    public PortalPdf billing(UUID id) {
        PortalBilling b = portal.billing(id);
        PortalAccount a = portal.account();
        List<ReportTable.Row> rows = new ArrayList<>();
        for (BillingLineView l : b.lines()) {
            rows.add(new ReportTable.Row(l.description(), 0, false, Arrays.asList(l.netAmount())));
        }
        rows.add(new ReportTable.Row("Net amount", 0, true, Arrays.asList(b.netAmount())));
        rows.add(new ReportTable.Row("VAT", 0, false, Arrays.asList(b.vatAmount())));
        rows.add(new ReportTable.Row("Total amount", 0, true, Arrays.asList(b.grossAmount())));
        rows.add(new ReportTable.Row("Paid or credited", 0, false, Arrays.asList(b.settledAmount())));
        rows.add(new ReportTable.Row("Balance due", 0, true, Arrays.asList(b.openBalance())));
        List<String> subtitles = new ArrayList<>(List.of(ORG, kind(b.kind()) + " " + b.documentNumber(), a.customerName() + " (" + a.customerCode() + ")"
                + (a.tin() == null ? "" : ", TIN " + a.tin()), "Date " + b.documentDate() + ", due " + b.dueDate()
                + (b.billingPeriod() == null ? "" : ", billing period " + b.billingPeriod())));
        if (b.relatedInvoiceNumber() != null) {
            subtitles.add("Relates to " + b.relatedInvoiceNumber());
        }
        byte[] pdf = ReportExporters.export(new ReportTable(kind(b.kind()), subtitles, "Description", List.of("Amount"), rows,
                List.of(b.description(), NOT_OFFICIAL, generated())), "PDF");
        audit.record("PORTAL_DOCUMENT_DOWNLOADED", "BILLING_DOCUMENT", id, Map.of("document", b.documentNumber(), "user", CurrentActor.username()));
        return new PortalPdf(b.documentNumber().replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf", pdf);
    }

    @Transactional
    public PortalPdf statement(LocalDate from, LocalDate to) {
        Statement s = portal.statement(from, to);
        List<ReportTable.Row> rows = new ArrayList<>();
        rows.add(new ReportTable.Row("Opening balance", 0, true, Arrays.asList(null, null, null, null, s.openingBalance())));
        for (StatementLine l : s.lines()) {
            rows.add(new ReportTable.Row(l.date().toString(), 0, false, Arrays.asList(l.type(), l.reference(), l.charges().signum() == 0 ? null : l.charges(),
                    l.credits().signum() == 0 ? null : l.credits(), l.balance())));
        }
        rows.add(new ReportTable.Row("Closing balance", 0, true, Arrays.asList(null, null, null, null, s.closingBalance())));
        byte[] pdf = ReportExporters.export(new ReportTable("Statement of account", List.of(ORG, s.customerName() + " (" + s.customerCode() + ")",
                "From " + from + " to " + to), "Date", List.of("Type", "Reference", "Charges", "Credits", "Balance"), rows,
                List.of("Unapplied collections (credits on account) as of " + to + ": " + s.unappliedCollections().toPlainString() + ".",
                        "Posted records only. " + NOT_OFFICIAL, generated())), "PDF");
        audit.record("PORTAL_DOCUMENT_DOWNLOADED", "CUSTOMER", s.customerCode(), Map.of("document", "STATEMENT " + from + " " + to,
                "user", CurrentActor.username()));
        return new PortalPdf("statement_" + s.customerCode() + "_" + from + "_" + to + ".pdf", pdf);
    }

    private static String kind(String k) {
        return switch (k) {
            case "CREDIT_MEMO" -> "Credit memo";
            case "DEBIT_MEMO" -> "Debit memo";
            default -> "Billing statement";
        };
    }

    private String generated() {
        return "Downloaded by " + CurrentActor.username() + " on " + STAMP.format(clock.instant()) + ".";
    }
}
