package com.miaa.cas.fixedassets;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.fixedassets.FaDtos.CategoryView;
import com.miaa.cas.fixedassets.FaDtos.RemeasurementRequest;
import com.miaa.cas.fixedassets.FaDtos.RemeasurementView;
import com.miaa.cas.generalledger.AccountMappingService;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Impairment (PAS 36) and revaluation (PAS 16) of an asset, each given as the new carrying amount: the recoverable
 * amount or the fair value. Prepared by one person, posted by another once depreciation is posted through the month
 * before; depreciation then restarts from the new carrying amount over the remaining (or reassessed) useful life.
 *
 * <ul>
 * <li>Impairment: Dr revaluation surplus (up to this asset's surplus) and Dr impairment loss / Cr accumulated impairment.</li>
 * <li>Revaluation, elimination method: accumulated depreciation and impairment are eliminated against the gross carrying
 * amount, which then moves to fair value. An increase first reverses decreases earlier charged to profit or loss, the
 * rest goes to revaluation surplus; a decrease first reduces this asset's surplus, the rest is a loss.</li>
 * </ul>
 *
 * Whether AVTECH uses the revaluation model is its policy (C-07, C-30): revaluation is refused unless
 * {@code cas.fixed-assets.revaluation-enabled} is on. Impairment applies under either model. Reversing an impairment
 * of an asset carried at cost is not supported.
 */
@Service
public class RemeasurementService {

    public static final String SOURCE = "SL_FA_REMEASUREMENT";

    private final FixedAssetService assets;
    private final AssetSetupService setup;
    private final DepreciationService depreciation;
    private final PostingService posting;
    private final AccountMappingService mappings;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean revaluationEnabled;

    public RemeasurementService(FixedAssetService assets, AssetSetupService setup, DepreciationService depreciation, PostingService posting,
            AccountMappingService mappings, AuditService audit, NotificationService notifications, JdbcTemplate jdbc, Clock clock,
            @Value("${cas.fixed-assets.revaluation-enabled:false}") boolean revaluationEnabled) {
        this.assets = assets;
        this.setup = setup;
        this.depreciation = depreciation;
        this.posting = posting;
        this.mappings = mappings;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
        this.revaluationEnabled = revaluationEnabled;
    }

    public boolean revaluationEnabled() {
        return revaluationEnabled;
    }

    @Transactional(readOnly = true)
    public RemeasurementView get(UUID id) {
        return view(id);
    }

    /** An asset's impairments and revaluations, newest first; cancelled drafts left out. */
    @Transactional(readOnly = true)
    public List<RemeasurementView> forAsset(UUID assetId) {
        return jdbc.queryForList("SELECT id FROM asset_remeasurements WHERE asset_id = ? AND status <> 'CANCELLED' "
                + "ORDER BY remeasured_on DESC, created_at DESC", UUID.class, assetId).stream().map(this::view).toList();
    }

    @Transactional
    public RemeasurementView prepare(RemeasurementRequest req) {
        CurrentActor.requirePermission("FA_MANAGE");
        if ("REVALUATION".equals(req.kind()) && !revaluationEnabled) {
            throw FixedAssetService.rule("Revaluation is turned off until AVTECH confirms it carries assets at revalued amounts (C-07, C-30).");
        }
        Map<String, Object> a = lockAsset(req.assetId());
        requireInService(a);
        if (req.remeasuredOn().isBefore(((Date) a.get("in_service_date")).toLocalDate())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The date cannot precede the in-service date.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM asset_remeasurements WHERE asset_id = ? AND status = 'DRAFT')",
                Boolean.class, req.assetId()))) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "An impairment or revaluation of this asset is already waiting to be posted.");
        }
        if ("IMPAIRMENT".equals(req.kind()) && setup.category((UUID) a.get("category_id")).accumulatedImpairmentAccountId() == null) {
            throw FixedAssetService.rule("Set the accumulated impairment account of category " + setup.category((UUID) a.get("category_id")).code()
                    + " first (Fixed assets > Setup).");
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO asset_remeasurements (id, asset_id, kind, remeasured_on, new_carrying_amount, remaining_life_months,
                    basis_reference, reason, status, prepared_by_id, prepared_by, created_at, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)""", id, req.assetId(), req.kind(), Date.valueOf(req.remeasuredOn()),
                Money.of(req.newCarryingAmount()), req.remainingLifeMonths(), blank(req.basisReference()), req.reason().trim(),
                CurrentActor.userId(), CurrentActor.username(), now, now, CurrentActor.username());
        audit.record("ASSET_" + req.kind() + "_PREPARED", "ASSET_REMEASUREMENT", id, Map.of("asset", String.valueOf(a.get("asset_number")),
                "newCarryingAmount", Money.of(req.newCarryingAmount()).toPlainString(), "date", req.remeasuredOn().toString()));
        return view(id);
    }

    @Transactional
    public RemeasurementView post(UUID id, long version) {
        CurrentActor.requirePermission("JOURNAL_POST");
        Map<String, Object> r = lockVersioned(id, version);
        if (!"DRAFT".equals(r.get("status"))) {
            throw FixedAssetService.state("Only drafts can be posted.");
        }
        if (CurrentActor.userId().equals(r.get("prepared_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You prepared this " + kindText(r) + " and cannot post it.");
        }
        String kind = (String) r.get("kind");
        if ("REVALUATION".equals(kind) && !revaluationEnabled) {
            throw FixedAssetService.rule("Revaluation is turned off until AVTECH confirms it carries assets at revalued amounts (C-07, C-30).");
        }
        UUID assetId = (UUID) r.get("asset_id");
        Map<String, Object> a = lockAsset(assetId);
        requireInService(a);
        LocalDate date = ((Date) r.get("remeasured_on")).toLocalDate();
        YearMonth month = YearMonth.from(date);
        if (assets.accumulated(assetId).compareTo(depreciation.expected(a, month.minusMonths(1))) < 0) {
            throw FixedAssetService.rule("Post depreciation through " + month.minusMonths(1) + " before posting this " + kindText(r) + ".");
        }
        List<String> charged = jdbc.queryForList("""
                SELECT p.name FROM depreciation_lines dl JOIN depreciation_runs dr ON dr.id = dl.run_id
                JOIN accounting_periods p ON p.id = dl.period_id
                WHERE dl.asset_id = ? AND dl.live AND dr.status <> 'CANCELLED' AND p.end_date >= ? ORDER BY p.end_date""",
                String.class, assetId, Date.valueOf(month.atDay(1)));
        if (!charged.isEmpty()) {
            throw FixedAssetService.rule("Depreciation for " + charged.get(0) + " already includes this asset; date the " + kindText(r)
                    + " in a later month, or cancel that run if it is still a draft.");
        }
        Calc c = calc(a, r);
        CategoryView cat = setup.category((UUID) a.get("category_id"));
        UUID cc = (UUID) a.get("cost_center_id");
        String number = (String) a.get("asset_number");
        List<LineData> lines = new ArrayList<>();
        if (c.depreciationEliminated.signum() > 0) {
            lines.add(line(cat.accumulatedAccountId(), cc, "Accumulated depreciation eliminated " + number, c.depreciationEliminated, Money.ZERO));
        }
        if (c.impairmentChange.signum() != 0) {
            UUID imp = cat.accumulatedImpairmentAccountId();
            if (imp == null) {
                throw FixedAssetService.rule("Set the accumulated impairment account of category " + cat.code() + " first (Fixed assets > Setup).");
            }
            lines.add(c.impairmentChange.signum() < 0
                    ? line(imp, cc, "Accumulated impairment eliminated " + number, c.impairmentChange.negate(), Money.ZERO)
                    : line(imp, cc, "Impairment of " + number, Money.ZERO, c.impairmentChange));
        }
        if (c.costChange.signum() != 0) {
            lines.add(c.costChange.signum() > 0
                    ? line(cat.assetAccountId(), cc, "Revaluation of " + number, c.costChange, Money.ZERO)
                    : line(cat.assetAccountId(), cc, "Revaluation of " + number, Money.ZERO, c.costChange.negate()));
        }
        if (c.surplusChange.signum() != 0) {
            UUID surplus = mappings.require("FA_REVALUATION_SURPLUS");
            lines.add(c.surplusChange.signum() > 0
                    ? line(surplus, cc, "Revaluation surplus " + number, Money.ZERO, c.surplusChange)
                    : line(surplus, cc, "Revaluation surplus used " + number, c.surplusChange.negate(), Money.ZERO));
        }
        if (c.lossInProfit.signum() > 0) {
            lines.add(line(mappings.require("FA_IMPAIRMENT_LOSS"), cc, ("IMPAIRMENT".equals(kind) ? "Impairment loss " : "Revaluation decrease ")
                    + number, c.lossInProfit, Money.ZERO));
        }
        if (c.reversalInProfit.signum() > 0) {
            lines.add(line(mappings.require("FA_IMPAIRMENT_REVERSAL"), cc, "Reversal of earlier decrease " + number, Money.ZERO,
                    c.reversalInProfit));
        }
        if (lines.isEmpty()) {
            throw FixedAssetService.rule("The new carrying amount equals the current one; there is nothing to post.");
        }
        PostingService.PostedEntry entry = posting.postSubledger(new PostingService.SubledgerEntry(SOURCE, id.toString(), date,
                ("IMPAIRMENT".equals(kind) ? "Impairment of " : "Revaluation of ") + number + " " + a.get("description") + ": " + r.get("reason"),
                lines, (UUID) r.get("prepared_by_id"), (String) r.get("prepared_by"), false));
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                UPDATE fixed_assets SET cost_adjustment = cost_adjustment + ?, depreciation_eliminated = depreciation_eliminated + ?,
                    accumulated_impairment = accumulated_impairment + ?, revaluation_surplus = revaluation_surplus + ?,
                    losses_in_profit = losses_in_profit + ? - ?, basis_month = ?, basis_accumulated = ?, basis_depreciable = ?,
                    basis_remaining_months = ?, status = ?, version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?""",
                c.costChange, c.depreciationEliminated, c.impairmentChange, c.surplusChange, c.lossInProfit, c.reversalInProfit,
                Date.valueOf(month.atDay(1)), c.basisAccumulated, c.depreciable, c.remainingMonths, c.depreciable.signum() > 0 ? "ACTIVE"
                        : "FULLY_DEPRECIATED", now, CurrentActor.username(), assetId);
        jdbc.update("""
                UPDATE asset_remeasurements SET status = 'POSTED', carrying_before = ?, cost_change = ?, depreciation_eliminated = ?,
                    impairment_change = ?, surplus_change = ?, loss_in_profit = ?, reversal_in_profit = ?, basis_remaining_months = ?,
                    posted_entry_id = ?, version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?""",
                c.carryingBefore, c.costChange, c.depreciationEliminated, c.impairmentChange, c.surplusChange, c.lossInProfit,
                c.reversalInProfit, c.remainingMonths, entry.entryId(), now, CurrentActor.username(), id);
        notifications.notify((UUID) r.get("prepared_by_id"), "DOCUMENT_POSTED", capitalised(kindText(r)) + " of " + number + " was posted by "
                + CurrentActor.username(), "Journal entry " + entry.entryNumber() + ".", "/fixed-assets/" + assetId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("asset", number);
        details.put("entryNumber", entry.entryNumber());
        details.put("carryingBefore", c.carryingBefore.toPlainString());
        details.put("carryingAfter", ((BigDecimal) r.get("new_carrying_amount")).toPlainString());
        details.put("lossInProfit", c.lossInProfit.toPlainString());
        details.put("surplusChange", c.surplusChange.toPlainString());
        audit.record("ASSET_" + kind + "_POSTED", "ASSET_REMEASUREMENT", id, details);
        return view(id);
    }

    @Transactional
    public RemeasurementView cancel(UUID id, long version) {
        Map<String, Object> r = lockVersioned(id, version);
        if (!"DRAFT".equals(r.get("status"))) {
            throw FixedAssetService.state("Only drafts can be cancelled.");
        }
        if (!CurrentActor.hasPermission("FA_MANAGE") && !CurrentActor.hasPermission("JOURNAL_POST")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot cancel this " + kindText(r) + ".");
        }
        jdbc.update("UPDATE asset_remeasurements SET status = 'CANCELLED', version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?",
                Timestamp.from(Instant.now(clock)), CurrentActor.username(), id);
        audit.record("ASSET_" + r.get("kind") + "_CANCELLED", "ASSET_REMEASUREMENT", id, Map.of());
        return view(id);
    }

    /** What posting a remeasurement records, from the asset's balances now. */
    private record Calc(BigDecimal carryingBefore, BigDecimal costChange, BigDecimal depreciationEliminated, BigDecimal impairmentChange,
            BigDecimal surplusChange, BigDecimal lossInProfit, BigDecimal reversalInProfit, BigDecimal basisAccumulated,
            BigDecimal depreciable, int remainingMonths) {
    }

    private Calc calc(Map<String, Object> a, Map<String, Object> r) {
        UUID assetId = (UUID) a.get("id");
        BigDecimal gross = Money.of(((BigDecimal) a.get("cost")).add((BigDecimal) a.get("cost_adjustment")));
        BigDecimal acc = assets.accumulated(assetId);
        BigDecimal impairment = (BigDecimal) a.get("accumulated_impairment");
        BigDecimal surplus = (BigDecimal) a.get("revaluation_surplus");
        BigDecimal priorLosses = (BigDecimal) a.get("losses_in_profit");
        BigDecimal carrying = Money.of(gross.subtract(acc).subtract(impairment));
        BigDecimal target = Money.of((BigDecimal) r.get("new_carrying_amount"));
        YearMonth month = YearMonth.from(((Date) r.get("remeasured_on")).toLocalDate());
        int remaining = r.get("remaining_life_months") != null ? ((Number) r.get("remaining_life_months")).intValue() : remainingMonths(a, month);
        BigDecimal depreciable = Money.of(target.subtract((BigDecimal) a.get("residual_value")).max(BigDecimal.ZERO));
        if (depreciable.signum() > 0 && remaining <= 0) {
            throw FixedAssetService.rule("The asset has no useful life left on its schedule; enter the reassessed remaining useful life.");
        }
        if ("IMPAIRMENT".equals(r.get("kind"))) {
            if (target.compareTo(carrying) >= 0) {
                throw FixedAssetService.rule("The recoverable amount must be below the carrying amount (" + carrying.toPlainString()
                        + "); there is nothing to impair.");
            }
            BigDecimal loss = carrying.subtract(target);
            BigDecimal fromSurplus = loss.min(surplus);
            return new Calc(carrying, Money.ZERO, Money.ZERO, loss, fromSurplus.negate(), Money.of(loss.subtract(fromSurplus)), Money.ZERO,
                    acc, depreciable, remaining);
        }
        BigDecimal diff = target.subtract(carrying);
        BigDecimal surplusChange;
        BigDecimal loss = Money.ZERO;
        BigDecimal reversal = Money.ZERO;
        if (diff.signum() >= 0) {
            reversal = diff.min(priorLosses);
            surplusChange = Money.of(diff.subtract(reversal));
        } else {
            BigDecimal decrease = diff.negate();
            BigDecimal fromSurplus = decrease.min(surplus);
            surplusChange = fromSurplus.negate();
            loss = Money.of(decrease.subtract(fromSurplus));
        }
        return new Calc(carrying, Money.of(target.subtract(gross)), acc, impairment.negate(), surplusChange, loss, reversal, Money.ZERO,
                depreciable, remaining);
    }

    /** Months left on the asset's current depreciation schedule, counting {@code from}. */
    private int remainingMonths(Map<String, Object> a, YearMonth from) {
        Date migration = (Date) a.get("depreciation_basis_date");
        Date remeasured = (Date) a.get("basis_month");
        YearMonth last = DepreciationMath.lastMonth(((Date) a.get("in_service_date")).toLocalDate(),
                ((Number) a.get("useful_life_months")).intValue(), assets.depreciationStart(),
                migration == null ? null : migration.toLocalDate(), a.get("remaining_life_months") == null ? null
                        : ((Number) a.get("remaining_life_months")).intValue(),
                remeasured == null ? null : remeasured.toLocalDate(), a.get("basis_remaining_months") == null ? null
                        : ((Number) a.get("basis_remaining_months")).intValue());
        return (int) Math.max(0, from.until(last, ChronoUnit.MONTHS) + 1);
    }

    private LineData line(UUID account, UUID assetCostCenter, String text, BigDecimal debit, BigDecimal credit) {
        return new LineData(account, assets.costCenterFor(account, assetCostCenter), null, text, Money.of(debit), Money.of(credit));
    }

    private void requireInService(Map<String, Object> a) {
        String status = (String) a.get("status");
        if (!"ACTIVE".equals(status) && !"FULLY_DEPRECIATED".equals(status)) {
            throw FixedAssetService.rule("Only active or fully depreciated assets can be impaired or revalued.");
        }
        UUID id = (UUID) a.get("id");
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM asset_disposals WHERE asset_id = ? AND status <> 'CANCELLED')",
                Boolean.class, id))) {
            throw FixedAssetService.rule("The asset has a disposal in progress.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM asset_transfers WHERE asset_id = ? AND status = 'DRAFT')",
                Boolean.class, id))) {
            throw FixedAssetService.rule("The asset has a transfer waiting to be posted; post or cancel it first.");
        }
    }

    private Map<String, Object> lockAsset(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM fixed_assets WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Asset");
        }
        return rows.get(0);
    }

    private Map<String, Object> lockVersioned(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM asset_remeasurements WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Impairment or revaluation");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        return rows.get(0);
    }

    RemeasurementView view(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.*, a.asset_number, a.description, e.entry_number FROM asset_remeasurements r
                JOIN fixed_assets a ON a.id = r.asset_id LEFT JOIN journal_entries e ON e.id = r.posted_entry_id WHERE r.id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Impairment or revaluation");
        }
        Map<String, Object> r = rows.get(0);
        String status = (String) r.get("status");
        Calc c = null;
        if ("POSTED".equals(status)) {
            c = new Calc((BigDecimal) r.get("carrying_before"), (BigDecimal) r.get("cost_change"), (BigDecimal) r.get("depreciation_eliminated"),
                    (BigDecimal) r.get("impairment_change"), (BigDecimal) r.get("surplus_change"), (BigDecimal) r.get("loss_in_profit"),
                    (BigDecimal) r.get("reversal_in_profit"), null, null, ((Number) r.get("basis_remaining_months")).intValue());
        } else if ("DRAFT".equals(status)) {
            try {
                c = calc(jdbc.queryForMap("SELECT * FROM fixed_assets WHERE id = ?", r.get("asset_id")), r);
            } catch (ApiException e) {
                // The preview is only informative; posting reports the same problem.
            }
        }
        List<String> actions = new ArrayList<>();
        if ("DRAFT".equals(status)) {
            if (CurrentActor.isPresent() && !CurrentActor.userId().equals(r.get("prepared_by_id")) && CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("POST");
            }
            if (CurrentActor.hasPermission("FA_MANAGE") || CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("CANCEL");
            }
        }
        return new RemeasurementView(id, (UUID) r.get("asset_id"), (String) r.get("asset_number"), (String) r.get("description"),
                (String) r.get("kind"), ((Date) r.get("remeasured_on")).toLocalDate(), c == null ? null : c.carryingBefore,
                (BigDecimal) r.get("new_carrying_amount"), r.get("remaining_life_months") == null ? null
                        : ((Number) r.get("remaining_life_months")).intValue(), (String) r.get("basis_reference"), (String) r.get("reason"),
                status, c == null ? null : c.costChange, c == null ? null : c.depreciationEliminated, c == null ? null : c.impairmentChange,
                c == null ? null : c.surplusChange, c == null ? null : c.lossInProfit, c == null ? null : c.reversalInProfit,
                c == null ? null : c.remainingMonths, (String) r.get("prepared_by"), (UUID) r.get("posted_entry_id"),
                (String) r.get("entry_number"), actions, ((Number) r.get("version")).longValue());
    }

    private static String kindText(Map<String, Object> r) {
        return "IMPAIRMENT".equals(r.get("kind")) ? "impairment" : "revaluation";
    }

    private static String capitalised(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
