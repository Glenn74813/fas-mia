package com.miaa.cas.financialreporting;

import com.miaa.cas.accountspayable.ApDtos.AgingReport;
import com.miaa.cas.accountspayable.ApDtos.AgingRow;
import com.miaa.cas.accountspayable.ApReportService;
import com.miaa.cas.accountsreceivable.ArDtos.ArAgingReport;
import com.miaa.cas.accountsreceivable.ArDtos.ArAgingRow;
import com.miaa.cas.accountsreceivable.ArReportService;
import com.miaa.cas.audit.EventStore;
import com.miaa.cas.budgeting.BudgetControl;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.financialreporting.export.ReportTable;
import com.miaa.cas.fixedassets.FaDtos.PpeSchedule;
import com.miaa.cas.fixedassets.FaDtos.ScheduleRow;
import com.miaa.cas.fixedassets.FaReportService;
import com.miaa.cas.inventoryaccounting.InvDtos.ItemView;
import com.miaa.cas.inventoryaccounting.InventorySetupService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Management reports and the audit-trail export, rendered as {@link ReportTable}s for the report job
 * queue. Each reads the same services and queries as the on-screen reports, so screen and export agree.
 */
@Service
public class ManagementReportService {

    public record Rendered(ReportTable table, String baseName) {
    }

    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(MANILA);
    private static final String ORG = "Manila International Airport Authority";

    private final JdbcTemplate jdbc;
    private final BudgetControl budget;
    private final ApReportService ap;
    private final ArReportService ar;
    private final FaReportService fa;
    private final InventorySetupService inventory;
    private final EventStore events;
    private final int auditRowLimit;
    private final VarianceReportService variance;
    private final OperationalReportService operational;

    public ManagementReportService(JdbcTemplate jdbc, BudgetControl budget, ApReportService ap, ArReportService ar, FaReportService fa,
            InventorySetupService inventory, EventStore events, @Value("${cas.reports.audit-row-limit:100000}") int auditRowLimit,
            OperationalReportService operational, VarianceReportService variance) {
        this.operational = operational;
        this.variance = variance;
        this.jdbc = jdbc;
        this.budget = budget;
        this.ap = ap;
        this.ar = ar;
        this.fa = fa;
        this.inventory = inventory;
        this.events = events;
        this.auditRowLimit = auditRowLimit;
    }

    /** The permission needed to request and download each report type. */
    public static String permissionFor(String reportType, Map<String, String> parameters) {
        return switch (reportType) {
            case "BUDGET_VS_ACTUAL", VarianceReportService.TYPE -> "BUDGET_VIEW";
            case "AP_AGING" -> "AP_VIEW";
            case "AR_AGING" -> "AR_VIEW";
            case "PPE_SCHEDULE" -> "FA_VIEW";
            case "INVENTORY_STOCK" -> "INVENTORY_VIEW";
            case "AUDIT_TRAIL" -> "SECURITY".equals(parameters.get("table")) ? "SECURITY_EVENT_VIEW" : "AUDIT_LOG_VIEW";
            default -> OperationalReportService.handles(reportType) ? OperationalReportService.permissionFor(reportType) : "FINANCIAL_REPORT_EXPORT";
        };
    }

    /** Fails fast on missing or malformed parameters when the job is requested. */
    public void validate(String reportType, Map<String, String> p) {
        switch (reportType) {
            case "BUDGET_VS_ACTUAL" -> {
                UUID fy = uuid(p, "fiscalYearId");
                if (jdbc.queryForList("SELECT id FROM budgets WHERE fiscal_year_id = ?", UUID.class, fy).isEmpty()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "There is no budget for that fiscal year.");
                }
            }
            case VarianceReportService.TYPE -> variance.validate(p);
            case "AP_AGING", "AR_AGING" -> {
                date(p, "asOf");
                for (String key : List.of("customerId", "supplierId")) {
                    if (p.get(key) != null && !p.get(key).isBlank()) {
                        uuid(p, key);
                    }
                }
            }
            case "PPE_SCHEDULE", "AUDIT_TRAIL" -> {
                if (date(p, "to").isBefore(date(p, "from"))) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "'to' cannot precede 'from'.");
                }
                if ("AUDIT_TRAIL".equals(reportType) && p.get("table") != null && !List.of("AUDIT", "SECURITY").contains(p.get("table"))) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "table must be AUDIT or SECURITY.");
                }
            }
            default -> {
                if (OperationalReportService.handles(reportType)) {
                    operational.validate(reportType, p);
                }
                // INVENTORY_STOCK takes no parameters
            }
        }
    }

    @Transactional(readOnly = true)
    public Rendered render(String reportType, Map<String, String> p, String by, String at) {
        return switch (reportType) {
            case "BUDGET_VS_ACTUAL" -> budgetVsActual(uuid(p, "fiscalYearId"), by, at);
            case VarianceReportService.TYPE -> variance.render(p, by, at);
            case "AP_AGING" -> apAging(date(p, "asOf"), blank(p.get("supplierId")) ? null : uuid(p, "supplierId"), by, at);
            case "AR_AGING" -> arAging(date(p, "asOf"), blank(p.get("customerId")) ? null : uuid(p, "customerId"), by, at);
            case "PPE_SCHEDULE" -> ppe(date(p, "from"), date(p, "to"), by, at);
            case "INVENTORY_STOCK" -> stock(by, at);
            case "AUDIT_TRAIL" -> audit("SECURITY".equals(p.get("table")) ? EventStore.Table.SECURITY : EventStore.Table.AUDIT,
                    date(p, "from"), date(p, "to"), p.get("action"), p.get("actor"), by, at);
            default -> {
                if (!OperationalReportService.handles(reportType)) {
                    throw new IllegalArgumentException(reportType);
                }
                yield operational.render(reportType, p, by, at);
            }
        };
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ------------------------------------------------------------------ reports

    private Rendered budgetVsActual(UUID fiscalYearId, String by, String at) {
        Map<String, Object> b = jdbc.queryForMap("SELECT b.id, b.name, b.status, y.fiscal_year FROM budgets b JOIN fiscal_years y "
                + "ON y.id = b.fiscal_year_id WHERE b.fiscal_year_id = ?", fiscalYearId);
        List<ReportTable.Row> rows = new ArrayList<>();
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Map<String, Object> a : jdbc.queryForList("""
                SELECT ba.id, ba.account_id, a.code, a.name, ba.cost_center_id, cc.code AS cc, ba.amount
                FROM budget_allocations ba JOIN chart_of_accounts a ON a.id = ba.account_id LEFT JOIN cost_centers cc ON cc.id = ba.cost_center_id
                WHERE ba.budget_id = ? ORDER BY a.code, cc.code NULLS FIRST""", b.get("id"))) {
            BigDecimal allot = Money.of((BigDecimal) a.get("amount"));
            BigDecimal reserved = budget.reserved((UUID) a.get("id"));
            BigDecimal actual = budget.actual((UUID) a.get("account_id"), (UUID) a.get("cost_center_id"), fiscalYearId);
            BigDecimal available = Money.of(allot.subtract(reserved).subtract(actual));
            String label = a.get("code") + " " + a.get("name") + (a.get("cc") == null ? "" : " / " + a.get("cc"));
            rows.add(new ReportTable.Row(label, 0, false, List.of(allot, reserved, actual, available, pct(reserved.add(actual), allot))));
            t[0] = t[0].add(allot);
            t[1] = t[1].add(reserved);
            t[2] = t[2].add(actual);
            t[3] = t[3].add(available);
        }
        rows.add(new ReportTable.Row("Total", 0, true, List.of(Money.of(t[0]), Money.of(t[1]), Money.of(t[2]), Money.of(t[3]),
                pct(t[1].add(t[2]), t[0]))));
        return new Rendered(new ReportTable("Budget utilisation (allotment vs reservations and actual)", List.of(ORG,
                b.get("name") + " — fiscal year " + b.get("fiscal_year") + " (" + b.get("status") + ")"), "Account / cost centre",
                List.of("Allotment", "Reserved", "Actual", "Available", "Utilised %"), rows, List.of(
                        "Actual = posted expenditure on the account and cost centre in the fiscal year. Reserved = submitted documents "
                                + "not yet posted. Budget presentation follows MIAA's budget definitions once confirmed (C-10).",
                        generated(by, at))), "budget_vs_actual_" + b.get("fiscal_year"));
    }

    private Rendered apAging(LocalDate asOf, UUID supplierId, String by, String at) {
        AgingReport r = ap.aging(asOf);
        List<ReportTable.Row> rows = new ArrayList<>();
        for (AgingRow a : r.rows()) {
            if (supplierId != null && !supplierId.equals(a.supplierId())) {
                continue;
            }
            rows.add(new ReportTable.Row(a.supplierCode() + " " + a.supplierName(), 0, false, agingValues(a.current(), a.days1to30(),
                    a.days31to60(), a.days61to90(), a.over90(), a.total())));
        }
        AgingRow t = supplierId == null ? r.totals() : r.rows().stream().filter(a -> supplierId.equals(a.supplierId())).findFirst()
                .orElse(new AgingRow(supplierId, null, null, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO));
        rows.add(new ReportTable.Row("Total", 0, true, agingValues(t.current(), t.days1to30(), t.days31to60(), t.days61to90(), t.over90(),
                t.total())));
        return new Rendered(new ReportTable("Aging of accounts payable", List.of(ORG, "As of " + asOf), "Supplier", AGING_COLUMNS, rows,
                List.of("Days past due date; payments count when their disbursement voucher was posted on or before the date.",
                        generated(by, at))), "ap_aging_" + asOf);
    }

    private Rendered arAging(LocalDate asOf, UUID customerId, String by, String at) {
        ArAgingReport r = ar.aging(asOf, customerId);
        List<ReportTable.Row> rows = new ArrayList<>();
        for (ArAgingRow a : r.rows()) {
            rows.add(new ReportTable.Row(a.customerCode() + " " + a.customerName(), 0, false, agingValues(a.current(), a.days1to30(),
                    a.days31to60(), a.days61to90(), a.over90(), a.total())));
        }
        ArAgingRow t = r.totals();
        rows.add(new ReportTable.Row("Total", 0, true, agingValues(t.current(), t.days1to30(), t.days31to60(), t.days61to90(), t.over90(),
                t.total())));
        return new Rendered(new ReportTable("Aging of accounts receivable", List.of(ORG, "As of " + asOf), "Customer", AGING_COLUMNS, rows,
                List.of("Days past due date; collections and credit memos count when posted on or before the date.", generated(by, at))),
                "ar_aging_" + asOf);
    }

    private Rendered ppe(LocalDate from, LocalDate to, String by, String at) {
        PpeSchedule s = fa.schedule(from, to);
        List<ReportTable.Row> rows = new ArrayList<>();
        for (ScheduleRow r : s.rows()) {
            rows.add(new ReportTable.Row(r.categoryCode() + " " + r.categoryName(), 0, false, ppeValues(r)));
        }
        rows.add(new ReportTable.Row("Total", 0, true, ppeValues(s.totals())));
        return new Rendered(new ReportTable("Schedule of property, plant and equipment", List.of(ORG, "From " + from + " to " + to),
                "Category", List.of("Cost, opening", "Additions", "Disposals", "Cost, closing", "Accum., opening", "Depreciation",
                        "Disposals", "Accum., closing", "Net book value"), rows, List.of(s.note(), generated(by, at))),
                "ppe_schedule_" + from + "_" + to);
    }

    private Rendered stock(String by, String at) {
        List<ReportTable.Row> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ItemView i : inventory.allItems()) {
            rows.add(new ReportTable.Row(i.code() + " " + i.name(), 0, false, List.of(i.unitOfMeasure(), i.inventoryAccountCode(),
                    i.quantity().stripTrailingZeros().toPlainString(), i.averageCost() == null ? "" : i.averageCost().stripTrailingZeros().toPlainString(),
                    i.value())));
            total = total.add(i.value());
        }
        List<Object> totals = new ArrayList<>(java.util.Arrays.asList(null, null, null, null, Money.of(total)));
        rows.add(new ReportTable.Row("Total inventory at weighted average cost", 0, true, totals));
        return new Rendered(new ReportTable("Inventory valuation", List.of(ORG, "As at " + TS.format(java.time.Instant.now())), "Item",
                List.of("Unit", "Account", "Quantity", "Average cost", "Value"), rows, List.of(generated(by, at))), "inventory_valuation");
    }

    /**
     * Audit or security events in a date range (Manila time), each with its row hash, plus the result of
     * re-verifying the whole hash chain at export time. Text cells are exported as text, never formulas.
     */
    private Rendered audit(EventStore.Table table, LocalDate from, LocalDate to, String action, String actor, String by, String at) {
        String sql = "SELECT id, occurred_at, actor_username, actor_roles, action, resource_type, resource_id, outcome, details::text AS details, row_hash"
                + (table == EventStore.Table.SECURITY ? ", severity" : ", NULL AS severity") + " FROM "
                + (table == EventStore.Table.SECURITY ? "security_events" : "audit_events")
                + " WHERE occurred_at >= ? AND occurred_at < ? AND (?::text IS NULL OR action = ?::text)"
                + " AND (?::text IS NULL OR actor_username = ?::text) ORDER BY id LIMIT ?";
        Timestamp start = Timestamp.from(from.atStartOfDay(MANILA).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(MANILA).toInstant());
        String act = action == null || action.isBlank() ? null : action.trim();
        String who = actor == null || actor.isBlank() ? null : actor.trim().toLowerCase();
        List<ReportTable.Row> rows = jdbc.query(sql, (rs, i) -> new ReportTable.Row(String.valueOf(rs.getLong("id")), 0, false,
                List.of(TS.format(rs.getTimestamp("occurred_at").toInstant()), nz(rs.getString("actor_username")), nz(rs.getString("actor_roles")),
                        rs.getString("action"),
                        rs.getString("resource_type") + (rs.getString("resource_id") == null ? "" : " " + rs.getString("resource_id")),
                        rs.getString("outcome") + (rs.getString("severity") == null ? "" : " / " + rs.getString("severity")),
                        rs.getString("details"), rs.getString("row_hash"))), start, end, act, act, who, who, auditRowLimit + 1);
        if (rows.size() > auditRowLimit) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "More than " + auditRowLimit + " events match; narrow the date range or filters.");
        }
        EventStore.ChainVerification v = events.verify(table);
        String chain = v.intact() ? "Hash chain verified intact at export: " + v.rowsChecked() + " events checked."
                : "HASH CHAIN BROKEN at sequence " + v.firstInvalidSequence() + " — report to Information Security.";
        String filters = (act == null ? "" : " · action " + act) + (who == null ? "" : " · actor " + who);
        return new Rendered(new ReportTable(table == EventStore.Table.SECURITY ? "Security event log" : "Audit trail", List.of(ORG,
                from + " to " + to + " (Asia/Manila)" + filters, rows.size() + " events"), "Sequence", List.of("Occurred", "Actor", "Roles", "Action",
                "Resource", "Outcome", "Details", "Row hash"), rows, List.of(chain,
                "Each row hash covers the event and the previous row's hash; the file's own SHA-256 is recorded with the export.",
                generated(by, at))), (table == EventStore.Table.SECURITY ? "security_events_" : "audit_trail_") + from + "_" + to);
    }

    // ------------------------------------------------------------------ helpers

    private static final List<String> AGING_COLUMNS = List.of("Current", "1-30 days", "31-60 days", "61-90 days", "Over 90 days", "Total");

    private static List<Object> agingValues(BigDecimal... v) {
        return List.of((Object[]) v);
    }

    private static List<Object> ppeValues(ScheduleRow r) {
        return List.of(r.openingCost(), r.additions(), r.disposalsCost(), r.closingCost(), r.openingAccumulated(), r.depreciation(),
                r.disposalsAccumulated(), r.closingAccumulated(), r.netBookValue());
    }

    private static BigDecimal pct(BigDecimal used, BigDecimal allot) {
        return allot.signum() == 0 ? BigDecimal.ZERO.setScale(1) : used.multiply(BigDecimal.valueOf(100)).divide(allot, 1, RoundingMode.HALF_EVEN);
    }

    private static String generated(String by, String at) {
        return "Generated " + at + " by " + by + ".";
    }

    private static String nz(String s) {
        return s == null ? "system" : s;
    }

    private static UUID uuid(Map<String, String> p, String key) {
        try {
            return UUID.fromString(require(p, key));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "parameters." + key + " is not a valid id.");
        }
    }

    private static LocalDate date(Map<String, String> p, String key) {
        try {
            return LocalDate.parse(require(p, key));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "parameters." + key + " must be yyyy-MM-dd.");
        }
    }

    private static String require(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "parameters." + key + " is required.");
        }
        return v.trim();
    }
}
