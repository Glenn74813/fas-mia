package com.miaa.cas.fixedassets;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.fixedassets.FaDtos.AssetRequest;
import com.miaa.cas.fixedassets.FaDtos.AssetSummary;
import com.miaa.cas.fixedassets.FaDtos.AssetView;
import com.miaa.cas.fixedassets.FaDtos.CategoryView;
import com.miaa.cas.fixedassets.FaDtos.DepreciationHistory;
import com.miaa.cas.fixedassets.FaDtos.ProjectView;
import com.miaa.cas.journal.NumberingService;
import com.miaa.cas.journal.PostingService;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The asset register. An asset is prepared as a draft and activated by a second person with posting
 * authority, which also records the acquisition in the ledger according to its source:
 * <ul>
 * <li>INVOICE: already recognised by the posted supplier invoice (no new entry); its cost must fit the
 * invoice lines charged to the category's asset account.</li>
 * <li>AUC: capitalised from a construction project: Dr asset / Cr construction in progress (project SL).</li>
 * <li>OTHER (donation, transfer-in, found asset): Dr asset / Cr the justified offset account.</li>
 * </ul>
 */
@Service
public class FixedAssetService {

    public static final String SOURCE = "SL_FA_ACQUISITION";

    private final AssetSetupService setup;
    private final NumberingService numbering;
    private final PostingService posting;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final DepreciationMath.Start start;
    private final org.springframework.beans.factory.ObjectProvider<DisposalService> disposals;

    public FixedAssetService(AssetSetupService setup, NumberingService numbering, PostingService posting, AuditService audit, NotificationService notifications,
            JdbcTemplate jdbc, Clock clock, @Value("${cas.fixed-assets.depreciation-start:NEXT_MONTH}") DepreciationMath.Start start,
            org.springframework.beans.factory.ObjectProvider<DisposalService> disposals) {
        this.disposals = disposals;
        this.setup = setup;
        this.numbering = numbering;
        this.posting = posting;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
        this.start = start;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<AssetSummary> list(String status, UUID categoryId, String q, int page, int size) {
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
        return JdbcPaging.query(jdbc, """
                SELECT a.*, c.code AS cat_code, """ + ACCUMULATED + """
                 AS acc FROM fixed_assets a JOIN asset_categories c ON c.id = a.category_id
                WHERE (?::text IS NULL OR a.status = ?::text) AND (?::uuid IS NULL OR a.category_id = ?::uuid)
                  AND (?::text IS NULL OR lower(coalesce(a.asset_number, '') || ' ' || a.description || ' ' || coalesce(a.serial_number, ''))
                       LIKE ?::text)
                ORDER BY a.asset_number NULLS FIRST, a.created_at DESC, a.id""", (rs, i) -> {
            BigDecimal cost = rs.getBigDecimal("cost");
            BigDecimal acc = Money.of(rs.getBigDecimal("acc"));
            return new AssetSummary(rs.getObject("id", UUID.class), rs.getString("asset_number"), rs.getString("description"),
                    rs.getString("cat_code"), rs.getString("location"), rs.getString("custodian"),
                    rs.getDate("in_service_date").toLocalDate(), cost, acc, Money.of(cost.subtract(acc)), rs.getString("status"),
                    rs.getLong("version"));
        }, page, size, st, st, categoryId, categoryId, like, like);
    }

    @Transactional(readOnly = true)
    public AssetView get(UUID id) {
        return view(id);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public AssetView create(AssetRequest req) {
        CurrentActor.requirePermission("FA_MANAGE");
        CategoryView cat = setup.category(req.categoryId());
        if (!"ACTIVE".equals(cat.status())) {
            throw rule("Category " + cat.code() + " is inactive.");
        }
        if (req.inServiceDate().isBefore(req.acquisitionDate())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The in-service date cannot precede the acquisition date.");
        }
        BigDecimal cost = Money.of(req.cost());
        int life = req.usefulLifeMonths() != null ? req.usefulLifeMonths() : cat.defaultUsefulLifeMonths();
        BigDecimal residual = req.residualValue() != null ? Money.of(req.residualValue())
                : cat.defaultResidualRate() == null ? Money.ZERO : cost.multiply(cat.defaultResidualRate()).setScale(2, RoundingMode.HALF_EVEN);
        if (residual.compareTo(cost) >= 0) {
            throw rule("The residual value must be less than the cost.");
        }
        UUID invoice = null;
        UUID project = null;
        UUID offset = null;
        String justification = blank(req.justification());
        switch (req.acquisitionSource()) {
            case "INVOICE" -> {
                invoice = require(req.supplierInvoiceId(), "the supplier invoice");
                checkInvoiceCapacity(invoice, cat, cost, null);
            }
            case "AUC" -> {
                project = require(req.aucProjectId(), "the AUC project");
                checkProjectBalance(project, cost);
            }
            default -> {
                offset = require(req.offsetAccountId(), "the offset account");
                if (justification == null || justification.length() < 10) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Explain the acquisition (at least 10 characters).");
                }
                Map<String, Object> a = jdbc.queryForMap("SELECT code, postable, status, subsidiary_type FROM chart_of_accounts WHERE id = ?", offset);
                if (!Boolean.TRUE.equals(a.get("postable")) || !"ACTIVE".equals(a.get("status")) || a.get("subsidiary_type") != null) {
                    throw rule("Account " + a.get("code") + " cannot be the offset (it must be postable, active and without a subsidiary ledger).");
                }
            }
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO fixed_assets (id, category_id, description, serial_number, location, custodian, cost_center_id,
                    acquisition_source, supplier_invoice_id, auc_project_id, offset_account_id, justification, acquisition_date,
                    in_service_date, cost, residual_value, useful_life_months, status, prepared_by_id, prepared_by, created_at,
                    updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)""", id, cat.id(),
                req.description().trim(), blank(req.serialNumber()), blank(req.location()), blank(req.custodian()), req.costCenterId(),
                req.acquisitionSource(), invoice, project, offset, justification, Date.valueOf(req.acquisitionDate()),
                Date.valueOf(req.inServiceDate()), cost, residual, life, CurrentActor.userId(), CurrentActor.username(), now, now,
                CurrentActor.username());
        audit.record("ASSET_REGISTERED", "FIXED_ASSET", id, Map.of("category", cat.code(), "cost", cost.toPlainString(),
                "source", req.acquisitionSource()));
        return view(id);
    }

    /** Second-person activation: assigns the asset number and records the acquisition in the ledger. */
    @Transactional
    public AssetView activate(UUID id, long version) {
        CurrentActor.requirePermission("JOURNAL_POST");
        Map<String, Object> a = lockVersioned(id, version);
        if (!"DRAFT".equals(a.get("status"))) {
            throw state("Only draft assets can be activated.");
        }
        if (CurrentActor.userId().equals(a.get("prepared_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You registered this asset and cannot activate it.");
        }
        CategoryView cat = setup.category((UUID) a.get("category_id"));
        BigDecimal cost = (BigDecimal) a.get("cost");
        LocalDate inService = ((Date) a.get("in_service_date")).toLocalDate();
        String number = numbering.next("FA", inService.getYear());
        UUID entryId;
        String source = (String) a.get("acquisition_source");
        UUID costCenter = (UUID) a.get("cost_center_id");
        String text = number + " " + a.get("description");
        if ("INVOICE".equals(source)) {
            UUID invoice = (UUID) a.get("supplier_invoice_id");
            checkInvoiceCapacity(invoice, cat, cost, id);
            entryId = jdbc.queryForObject("SELECT posted_entry_id FROM supplier_invoices WHERE id = ?", UUID.class, invoice);
        } else {
            List<LineData> lines = new ArrayList<>();
            lines.add(new LineData(cat.assetAccountId(), costCenterFor(cat.assetAccountId(), costCenter), null, text, cost, Money.ZERO));
            if ("AUC".equals(source)) {
                UUID projectId = (UUID) a.get("auc_project_id");
                checkProjectBalance(projectId, cost);
                ProjectView p = setup.project(projectId);
                lines.add(new LineData(p.aucAccountId(), costCenterFor(p.aucAccountId(), p.costCenterId()), p.subsidiaryAccountId(),
                        "Capitalised from " + p.code(), Money.ZERO, cost));
            } else {
                UUID offset = (UUID) a.get("offset_account_id");
                lines.add(new LineData(offset, costCenterFor(offset, costCenter), null, "Acquisition " + number, Money.ZERO, cost));
            }
            entryId = posting.postSubledger(new PostingService.SubledgerEntry(SOURCE, id.toString(), inService,
                    "Asset acquisition " + text, lines, (UUID) a.get("prepared_by_id"), (String) a.get("prepared_by"), false)).entryId();
        }
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("UPDATE fixed_assets SET asset_number = ?, status = 'ACTIVE', activated_by = ?, activated_at = ?, "
                + "acquisition_entry_id = ?, version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?", number,
                CurrentActor.username(), now, entryId, now, CurrentActor.username(), id);
        notifications.notify((UUID) a.get("prepared_by_id"), "DOCUMENT_POSTED", "Asset " + number + " was activated by " + CurrentActor.username(),
                null, "/fixed-assets/" + id);
        audit.record("ASSET_ACTIVATED", "FIXED_ASSET", id, Map.of("assetNumber", number, "cost", cost.toPlainString()));
        return view(id);
    }

    @Transactional
    public AssetView cancel(UUID id, long version, String reason) {
        CurrentActor.requirePermission("FA_MANAGE");
        Map<String, Object> a = lockVersioned(id, version);
        if (!"DRAFT".equals(a.get("status"))) {
            throw state("Only draft assets can be cancelled; active assets are disposed of.");
        }
        jdbc.update("UPDATE fixed_assets SET status = 'CANCELLED', version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?",
                Timestamp.from(Instant.now(clock)), CurrentActor.username(), id);
        audit.record("ASSET_CANCELLED", "FIXED_ASSET", id, Map.of("reason", reason.trim()));
        return view(id);
    }

    // ------------------------------------------------------------------ helpers shared by depreciation and disposal

    /** Accumulated depreciation of an asset: migrated opening amount plus posted runs (SQL fragment over alias {@code a}). */
    static final String ACCUMULATED = """
            (a.opening_accumulated + coalesce((SELECT sum(dl.amount) FROM depreciation_lines dl JOIN depreciation_runs dr ON dr.id = dl.run_id
                      WHERE dl.asset_id = a.id AND dl.live AND dr.status = 'POSTED'), 0))""";

    BigDecimal accumulated(UUID assetId) {
        return Money.of(jdbc.queryForObject("SELECT " + ACCUMULATED + " FROM fixed_assets a WHERE a.id = ?", BigDecimal.class, assetId));
    }

    /** The cost centre is passed only to accounts that require one. */
    UUID costCenterFor(UUID accountId, UUID costCenter) {
        Boolean requires = jdbc.queryForObject("SELECT requires_cost_center FROM chart_of_accounts WHERE id = ?", Boolean.class, accountId);
        return Boolean.TRUE.equals(requires) ? costCenter : null;
    }

    private void checkInvoiceCapacity(UUID invoice, CategoryView cat, BigDecimal cost, UUID self) {
        List<Map<String, Object>> inv = jdbc.queryForList("SELECT invoice_number, status FROM supplier_invoices WHERE id = ? FOR UPDATE",
                invoice);
        if (inv.isEmpty()) {
            throw ApiException.notFound("Supplier invoice");
        }
        if (!"POSTED".equals(inv.get(0).get("status"))) {
            throw rule("Supplier invoice " + inv.get(0).get("invoice_number") + " must be posted first.");
        }
        BigDecimal capacity = Money.of(jdbc.queryForObject("SELECT coalesce(sum(net_amount), 0) FROM supplier_invoice_lines "
                + "WHERE invoice_id = ? AND account_id = ?", BigDecimal.class, invoice, cat.assetAccountId()));
        BigDecimal used = Money.of(jdbc.queryForObject("SELECT coalesce(sum(cost), 0) FROM fixed_assets WHERE supplier_invoice_id = ? "
                + "AND status IN ('ACTIVE', 'FULLY_DEPRECIATED', 'DISPOSED') AND (?::uuid IS NULL OR id <> ?::uuid)", BigDecimal.class,
                invoice, self, self));
        if (cost.compareTo(capacity.subtract(used)) > 0) {
            throw rule("Invoice " + inv.get(0).get("invoice_number") + " charged " + capacity.toPlainString() + " to "
                    + cat.assetAccountCode() + ", of which " + used.toPlainString() + " is already registered; "
                    + cost.toPlainString() + " does not fit.");
        }
    }

    private void checkProjectBalance(UUID projectId, BigDecimal cost) {
        jdbc.queryForList("SELECT id FROM auc_projects WHERE id = ? FOR UPDATE", projectId);
        ProjectView p = setup.project(projectId);
        if (!"OPEN".equals(p.status())) {
            throw rule("Project " + p.code() + " is closed.");
        }
        if (cost.compareTo(p.balance()) > 0) {
            throw rule("Project " + p.code() + " carries only " + p.balance().toPlainString() + " to capitalise.");
        }
    }

    private Map<String, Object> lockVersioned(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM fixed_assets WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Asset");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        return rows.get(0);
    }

    AssetView view(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.*, c.code AS cat_code, c.name AS cat_name, cc.code AS cc_code, si.invoice_number, p.code AS project_code,
                       o.code AS offset_code, e.entry_number FROM fixed_assets a JOIN asset_categories c ON c.id = a.category_id
                LEFT JOIN cost_centers cc ON cc.id = a.cost_center_id LEFT JOIN supplier_invoices si ON si.id = a.supplier_invoice_id
                LEFT JOIN auc_projects p ON p.id = a.auc_project_id LEFT JOIN chart_of_accounts o ON o.id = a.offset_account_id
                LEFT JOIN journal_entries e ON e.id = a.acquisition_entry_id WHERE a.id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Asset");
        }
        Map<String, Object> a = rows.get(0);
        BigDecimal cost = (BigDecimal) a.get("cost");
        BigDecimal residual = (BigDecimal) a.get("residual_value");
        int life = ((Number) a.get("useful_life_months")).intValue();
        BigDecimal acc = accumulated(id);
        List<DepreciationHistory> history = jdbc.query("""
                SELECT p.name, p.end_date, dl.amount, dr.status, e.entry_number FROM depreciation_lines dl
                JOIN depreciation_runs dr ON dr.id = dl.run_id JOIN accounting_periods p ON p.id = dl.period_id
                LEFT JOIN journal_entries e ON e.id = dr.posted_entry_id WHERE dl.asset_id = ? AND dl.live ORDER BY p.end_date""",
                (rs, i) -> new DepreciationHistory(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3), rs.getString(4),
                        rs.getString(5)), id);
        List<UUID> disposalIds = jdbc.queryForList("SELECT id FROM asset_disposals WHERE asset_id = ? AND status <> 'CANCELLED'",
                UUID.class, id);
        String status = (String) a.get("status");
        boolean preparer = CurrentActor.userId().equals(a.get("prepared_by_id"));
        List<String> actions = new ArrayList<>();
        if ("DRAFT".equals(status)) {
            if (!preparer && CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("ACTIVATE");
            }
            if (CurrentActor.hasPermission("FA_MANAGE")) {
                actions.add("CANCEL");
            }
        }
        if (("ACTIVE".equals(status) || "FULLY_DEPRECIATED".equals(status)) && disposalIds.isEmpty()
                && CurrentActor.hasPermission("FA_MANAGE")) {
            actions.add("DISPOSE");
        }
        Timestamp activatedAt = (Timestamp) a.get("activated_at");
        return new AssetView(id, (String) a.get("asset_number"), (String) a.get("description"), (String) a.get("serial_number"),
                (String) a.get("location"), (String) a.get("custodian"), (UUID) a.get("category_id"), (String) a.get("cat_code"),
                (String) a.get("cat_name"), (UUID) a.get("cost_center_id"), (String) a.get("cc_code"), (String) a.get("acquisition_source"),
                (UUID) a.get("supplier_invoice_id"), (String) a.get("invoice_number"), (UUID) a.get("auc_project_id"),
                (String) a.get("project_code"), (String) a.get("offset_code"), (String) a.get("justification"),
                ((Date) a.get("acquisition_date")).toLocalDate(), ((Date) a.get("in_service_date")).toLocalDate(), cost, residual,
                life, a.get("depreciation_basis_date") == null ? DepreciationMath.monthly(cost, residual, life)
                        : DepreciationMath.monthlyFromOpening(cost, residual, (BigDecimal) a.get("opening_accumulated"),
                                ((Number) a.get("remaining_life_months")).intValue()), acc, Money.of(cost.subtract(acc)), status,
                (String) a.get("prepared_by"), (String) a.get("activated_by"), activatedAt == null ? null : activatedAt.toInstant(),
                (UUID) a.get("acquisition_entry_id"), (String) a.get("entry_number"), history,
                disposalIds.isEmpty() ? null : disposals.getObject().view(disposalIds.get(0)), actions, ((Number) a.get("version")).longValue());
    }

    /** The configured depreciation start convention (C-30). */
    public DepreciationMath.Start depreciationStart() {
        return start;
    }

    private static UUID require(UUID v, String what) {
        if (v == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Select " + what + ".");
        }
        return v;
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static ApiException state(String message) {
        return new ApiException(ErrorCode.INVALID_STATE, message);
    }

    static ApiException rule(String message) {
        return new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, message, Map.of("violations", List.of(message)));
    }
}
