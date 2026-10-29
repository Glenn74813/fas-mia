package com.miaa.cas.fixedassets;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.fixedassets.FaDtos.CategoryView;
import com.miaa.cas.fixedassets.FaDtos.DisposalRequest;
import com.miaa.cas.fixedassets.FaDtos.DisposalView;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.generalledger.AccountPurpose;
import com.miaa.cas.journal.PostingService;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derecognition of an asset (sale, scrap, donation, transfer out, loss). Prepared by one person, posted by
 * another once depreciation is posted through the month before disposal:
 * Dr accumulated depreciation + Dr proceeds / Cr asset cost, with the difference to gain or loss on disposal.
 * Whether depreciation is charged for the month of disposal is MIAA policy (C-30); none is charged here.
 */
@Service
public class DisposalService {

    public static final String SOURCE = "SL_FA_DISPOSAL";

    private final FixedAssetService assets;
    private final AssetSetupService setup;
    private final DepreciationService depreciation;
    private final PostingService posting;
    private final AccountMappingService mappings;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DisposalService(FixedAssetService assets, AssetSetupService setup, DepreciationService depreciation, PostingService posting,
            AccountMappingService mappings, AuditService audit, NotificationService notifications, JdbcTemplate jdbc, Clock clock) {
        this.assets = assets;
        this.setup = setup;
        this.depreciation = depreciation;
        this.posting = posting;
        this.mappings = mappings;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<DisposalView> list(int page, int size) {
        return JdbcPaging.ids(jdbc, "SELECT id FROM asset_disposals ORDER BY disposal_date DESC, created_at DESC, id", page, size)
                .map(this::view);
    }

    @Transactional(readOnly = true)
    public DisposalView get(UUID id) {
        return view(id);
    }

    @Transactional
    public DisposalView prepare(DisposalRequest req) {
        CurrentActor.requirePermission("FA_MANAGE");
        Map<String, Object> a = lockAsset(req.assetId());
        String status = (String) a.get("status");
        if (!"ACTIVE".equals(status) && !"FULLY_DEPRECIATED".equals(status)) {
            throw FixedAssetService.rule("Only active or fully depreciated assets can be disposed of.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM asset_transfers WHERE asset_id = ? AND status = 'DRAFT')",
                Boolean.class, req.assetId()))) {
            throw FixedAssetService.rule("The asset has a transfer waiting to be posted; post or cancel it first.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM asset_remeasurements WHERE asset_id = ? AND status = 'DRAFT')",
                Boolean.class, req.assetId()))) {
            throw FixedAssetService.rule("The asset has an impairment or revaluation waiting to be posted; post or cancel it first.");
        }
        if (req.disposalDate().isBefore(((Date) a.get("in_service_date")).toLocalDate())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The disposal date cannot precede the in-service date.");
        }
        BigDecimal proceeds = Money.of(req.proceeds());
        if (proceeds.signum() > 0) {
            if (req.proceedsAccountId() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Select the account that receives the proceeds.");
            }
            AccountPurpose.require(jdbc, req.proceedsAccountId(), AccountPurpose.CASH, "Proceeds received in");
        } else if ("SALE".equals(req.method())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A sale needs the proceeds.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM asset_disposals WHERE asset_id = ? AND status <> 'CANCELLED')",
                Boolean.class, req.assetId()))) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A disposal is already in progress for this asset.");
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO asset_disposals (id, asset_id, disposal_date, method, proceeds, proceeds_account_id, reason, status,
                    prepared_by_id, prepared_by, created_at, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)""", id, req.assetId(), Date.valueOf(req.disposalDate()), req.method(),
                proceeds, proceeds.signum() > 0 ? req.proceedsAccountId() : null, req.reason().trim(), CurrentActor.userId(),
                CurrentActor.username(), now, now, CurrentActor.username());
        audit.record("ASSET_DISPOSAL_PREPARED", "ASSET_DISPOSAL", id, Map.of("asset", String.valueOf(a.get("asset_number")),
                "method", req.method(), "proceeds", proceeds.toPlainString()));
        return view(id);
    }

    @Transactional
    public DisposalView post(UUID id, long version) {
        CurrentActor.requirePermission("JOURNAL_POST");
        Map<String, Object> d = lockVersioned(id, version);
        if (!"DRAFT".equals(d.get("status"))) {
            throw FixedAssetService.state("Only draft disposals can be posted.");
        }
        if (CurrentActor.userId().equals(d.get("prepared_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You prepared this disposal and cannot post it.");
        }
        UUID assetId = (UUID) d.get("asset_id");
        Map<String, Object> a = lockAsset(assetId);
        LocalDate date = ((Date) d.get("disposal_date")).toLocalDate();
        YearMonth before = YearMonth.from(date).minusMonths(1);
        BigDecimal acc = assets.accumulated(assetId);
        if (acc.compareTo(depreciation.expected(a, before)) < 0) {
            throw FixedAssetService.rule("Post depreciation through " + before + " before disposing of this asset.");
        }
        List<String> pending = jdbc.queryForList("""
                SELECT p.name FROM depreciation_lines dl JOIN depreciation_runs r ON r.id = dl.run_id
                JOIN accounting_periods p ON p.id = dl.period_id WHERE dl.asset_id = ? AND dl.live AND r.status = 'DRAFT'""",
                String.class, assetId);
        if (!pending.isEmpty()) {
            throw FixedAssetService.rule("A draft depreciation run for " + pending.get(0) + " includes this asset; post or cancel it first.");
        }
        CategoryView cat = setup.category((UUID) a.get("category_id"));
        BigDecimal cost = Money.of(((BigDecimal) a.get("cost")).add((BigDecimal) a.get("cost_adjustment")));
        BigDecimal impairment = (BigDecimal) a.get("accumulated_impairment");
        BigDecimal proceeds = (BigDecimal) d.get("proceeds");
        BigDecimal gainLoss = Money.of(proceeds.subtract(cost.subtract(acc).subtract(impairment)));
        String number = (String) a.get("asset_number");
        List<LineData> lines = new ArrayList<>();
        if (acc.signum() > 0) {
            lines.add(new LineData(cat.accumulatedAccountId(), assets.costCenterFor(cat.accumulatedAccountId(), (UUID) a.get("cost_center_id")), null,
                    "Accumulated depreciation " + number, acc, Money.ZERO));
        }
        if (impairment.signum() > 0) {
            UUID imp = cat.accumulatedImpairmentAccountId();
            if (imp == null) {
                throw FixedAssetService.rule("Set the accumulated impairment account of category " + cat.code() + " first (Fixed assets > Setup).");
            }
            lines.add(new LineData(imp, assets.costCenterFor(imp, (UUID) a.get("cost_center_id")), null, "Accumulated impairment " + number,
                    impairment, Money.ZERO));
        }
        if (proceeds.signum() > 0) {
            UUID pa = (UUID) d.get("proceeds_account_id");
            lines.add(new LineData(pa, assets.costCenterFor(pa, (UUID) a.get("cost_center_id")), null, "Proceeds " + number, proceeds,
                    Money.ZERO));
        }
        if (gainLoss.signum() < 0) {
            UUID loss = mappings.require("FA_DISPOSAL_LOSS");
            lines.add(new LineData(loss, assets.costCenterFor(loss, (UUID) a.get("cost_center_id")), null, "Loss on disposal " + number,
                    gainLoss.negate(), Money.ZERO));
        }
        lines.add(new LineData(cat.assetAccountId(), assets.costCenterFor(cat.assetAccountId(), (UUID) a.get("cost_center_id")), null,
                "Cost of " + number, Money.ZERO, cost));
        if (gainLoss.signum() > 0) {
            UUID gain = mappings.require("FA_DISPOSAL_GAIN");
            lines.add(new LineData(gain, assets.costCenterFor(gain, (UUID) a.get("cost_center_id")), null, "Gain on disposal " + number,
                    Money.ZERO, gainLoss));
        }
        PostingService.PostedEntry entry = posting.postSubledger(new PostingService.SubledgerEntry(SOURCE, id.toString(), date,
                "Disposal (" + ((String) d.get("method")).toLowerCase() + ") of " + number + " " + a.get("description"), lines,
                (UUID) d.get("prepared_by_id"), (String) d.get("prepared_by"), false));
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("UPDATE asset_disposals SET status = 'POSTED', cost = ?, accumulated = ?, accumulated_impairment = ?, gain_loss = ?, "
                + "posted_entry_id = ?, version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?", cost, acc, impairment, gainLoss,
                entry.entryId(), now,
                CurrentActor.username(), id);
        jdbc.update("UPDATE fixed_assets SET status = 'DISPOSED', version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?",
                now, CurrentActor.username(), assetId);
        notifications.notify((UUID) d.get("prepared_by_id"), "DOCUMENT_POSTED", "Disposal of " + number + " was posted by "
                + CurrentActor.username(), "Journal entry " + entry.entryNumber() + ".", "/fixed-assets/" + assetId);
        audit.record("ASSET_DISPOSED", "ASSET_DISPOSAL", id, Map.of("asset", number, "entryNumber", entry.entryNumber(),
                "gainLoss", gainLoss.toPlainString()));
        return view(id);
    }

    @Transactional
    public DisposalView cancel(UUID id, long version) {
        Map<String, Object> d = lockVersioned(id, version);
        if (!"DRAFT".equals(d.get("status"))) {
            throw FixedAssetService.state("Only draft disposals can be cancelled.");
        }
        if (!CurrentActor.hasPermission("FA_MANAGE") && !CurrentActor.hasPermission("JOURNAL_POST")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot cancel this disposal.");
        }
        jdbc.update("UPDATE asset_disposals SET status = 'CANCELLED', version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?",
                Timestamp.from(Instant.now(clock)), CurrentActor.username(), id);
        audit.record("ASSET_DISPOSAL_CANCELLED", "ASSET_DISPOSAL", id, Map.of());
        return view(id);
    }

    private Map<String, Object> lockAsset(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM fixed_assets WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Asset");
        }
        return rows.get(0);
    }

    private Map<String, Object> lockVersioned(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM asset_disposals WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Disposal");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        return rows.get(0);
    }

    public DisposalView view(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.*, a.asset_number, a.description, a.cost + a.cost_adjustment AS asset_cost, a.accumulated_impairment AS asset_impairment,
                       pa.code AS pa_code, e.entry_number
                FROM asset_disposals d JOIN fixed_assets a ON a.id = d.asset_id LEFT JOIN chart_of_accounts pa ON pa.id = d.proceeds_account_id
                LEFT JOIN journal_entries e ON e.id = d.posted_entry_id WHERE d.id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Disposal");
        }
        Map<String, Object> d = rows.get(0);
        String status = (String) d.get("status");
        BigDecimal cost = d.get("cost") != null ? (BigDecimal) d.get("cost") : (BigDecimal) d.get("asset_cost");
        BigDecimal acc = d.get("accumulated") != null ? (BigDecimal) d.get("accumulated") : assets.accumulated((UUID) d.get("asset_id"));
        BigDecimal impairment = d.get("accumulated_impairment") != null ? (BigDecimal) d.get("accumulated_impairment")
                : (BigDecimal) d.get("asset_impairment");
        BigDecimal nbv = Money.of(cost.subtract(acc).subtract(impairment));
        BigDecimal gl = d.get("gain_loss") != null ? (BigDecimal) d.get("gain_loss") : Money.of(((BigDecimal) d.get("proceeds")).subtract(nbv));
        boolean preparer = CurrentActor.userId().equals(d.get("prepared_by_id"));
        List<String> actions = new ArrayList<>();
        if ("DRAFT".equals(status)) {
            if (!preparer && CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("POST");
            }
            if (CurrentActor.hasPermission("FA_MANAGE") || CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("CANCEL");
            }
        }
        return new DisposalView(id, (UUID) d.get("asset_id"), (String) d.get("asset_number"), (String) d.get("description"),
                ((Date) d.get("disposal_date")).toLocalDate(), (String) d.get("method"), (BigDecimal) d.get("proceeds"),
                (String) d.get("pa_code"), (String) d.get("reason"), status, cost, acc, impairment, nbv, gl, (String) d.get("prepared_by"),
                (UUID) d.get("posted_entry_id"), (String) d.get("entry_number"), actions, ((Number) d.get("version")).longValue());
    }
}
