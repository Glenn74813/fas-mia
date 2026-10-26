package com.miaa.cas.fixedassets;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.fixedassets.FaDtos.CategoryView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asset transfers between units (MS §11.5): another cost centre, location or custodian, with history. One person
 * prepares a transfer and a different person posts it. When the cost centre changes and the category's asset or
 * accumulated-depreciation account carries cost centres, posting reclassifies the cost and the accumulated
 * depreciation to the new cost centre in the ledger. Depreciation runs after the transfer charge the new cost centre.
 */
@Service
public class AssetTransferService {

    public static final String SOURCE = "SL_FA_TRANSFER";

    public record TransferView(UUID id, UUID assetId, String assetNumber, LocalDate transferDate, UUID fromCostCenterId, String fromCostCenter,
            UUID toCostCenterId, String toCostCenter, String fromLocation, String toLocation, String fromCustodian, String toCustodian,
            String reason, String status, String preparedBy, String postedBy, Instant postedAt, String entryNumber, List<String> availableActions,
            long version) {
    }

    private final FixedAssetService assets;
    private final AssetSetupService setup;
    private final PostingService posting;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AssetTransferService(FixedAssetService assets, AssetSetupService setup, PostingService posting, AuditService audit,
            NotificationService notifications, JdbcTemplate jdbc, Clock clock) {
        this.assets = assets;
        this.setup = setup;
        this.posting = posting;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TransferView> forAsset(UUID assetId) {
        return jdbc.queryForList("SELECT id FROM asset_transfers WHERE asset_id = ? ORDER BY transfer_date DESC, created_at DESC", UUID.class, assetId)
                .stream().map(this::view).toList();
    }

    @Transactional
    public TransferView prepare(UUID assetId, LocalDate transferDate, UUID toCostCenterId, String toLocation, String toCustodian, String reason) {
        CurrentActor.requirePermission("FA_MANAGE");
        Map<String, Object> a = jdbc.queryForList("SELECT * FROM fixed_assets WHERE id = ? FOR UPDATE", assetId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Asset"));
        if (!List.of("ACTIVE", "FULLY_DEPRECIATED").contains(a.get("status"))) {
            throw FixedAssetService.rule("Only an asset in service can be transferred.");
        }
        if (count("SELECT count(*) FROM asset_disposals WHERE asset_id = ? AND status = 'DRAFT'", assetId) > 0) {
            throw FixedAssetService.rule("The asset has a disposal in preparation.");
        }
        if (count("SELECT count(*) FROM asset_transfers WHERE asset_id = ? AND status = 'DRAFT'", assetId) > 0) {
            throw FixedAssetService.rule("The asset already has a transfer waiting to be posted.");
        }
        if (transferDate == null || transferDate.isBefore(((Date) a.get("in_service_date")).toLocalDate())) {
            throw FixedAssetService.rule("The transfer date cannot be before the asset was placed in service.");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give the reason for the transfer (at least 5 characters).");
        }
        if (toCostCenterId != null && count("SELECT count(*) FROM cost_centers WHERE id = ? AND status = 'ACTIVE'", toCostCenterId) == 0) {
            throw ApiException.notFound("Active cost centre");
        }
        String loc = blank(toLocation);
        String cust = blank(toCustodian);
        if (Objects.equals(toCostCenterId, a.get("cost_center_id")) && Objects.equals(loc, a.get("location")) && Objects.equals(cust, a.get("custodian"))) {
            throw FixedAssetService.rule("Nothing changes: give a different cost centre, location or custodian.");
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO asset_transfers (id, asset_id, transfer_date, from_cost_center_id, to_cost_center_id, from_location, to_location,
                    from_custodian, to_custodian, reason, status, prepared_by_id, prepared_by, created_at, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)""", id, assetId, Date.valueOf(transferDate), a.get("cost_center_id"),
                toCostCenterId, a.get("location"), loc, a.get("custodian"), cust, reason.trim(), CurrentActor.userId(), CurrentActor.username(),
                now, now, CurrentActor.username());
        audit.record("ASSET_TRANSFER_PREPARED", "FIXED_ASSET", assetId, Map.of("transfer", id.toString(), "date", transferDate.toString()));
        return view(id);
    }

    @Transactional
    public TransferView post(UUID id, long version) {
        CurrentActor.requirePermission("JOURNAL_POST");
        Map<String, Object> t = lock(id, version);
        if (CurrentActor.userId().equals(t.get("prepared_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You prepared this transfer and cannot post it.");
        }
        UUID assetId = (UUID) t.get("asset_id");
        Map<String, Object> a = jdbc.queryForMap("SELECT * FROM fixed_assets WHERE id = ? FOR UPDATE", assetId);
        if (!Objects.equals(a.get("cost_center_id"), t.get("from_cost_center_id")) || !Objects.equals(a.get("location"), t.get("from_location"))
                || !Objects.equals(a.get("custodian"), t.get("from_custodian"))) {
            throw FixedAssetService.rule("The asset changed after this transfer was prepared; cancel it and prepare it again.");
        }
        if (!List.of("ACTIVE", "FULLY_DEPRECIATED").contains(a.get("status"))) {
            throw FixedAssetService.rule("The asset is no longer in service.");
        }
        UUID from = (UUID) t.get("from_cost_center_id");
        UUID to = (UUID) t.get("to_cost_center_id");
        LocalDate date = ((Date) t.get("transfer_date")).toLocalDate();
        CategoryView cat = setup.category((UUID) a.get("category_id"));
        BigDecimal cost = (BigDecimal) a.get("cost");
        BigDecimal acc = assets.accumulated(assetId);
        String number = (String) a.get("asset_number");
        List<LineData> lines = new ArrayList<>();
        reclass(lines, cat.assetAccountId(), from, to, cost, true, number);
        reclass(lines, cat.accumulatedAccountId(), from, to, acc, false, number);
        UUID entryId = null;
        if (!lines.isEmpty()) {
            entryId = posting.postSubledger(new PostingService.SubledgerEntry(SOURCE, id.toString(), date, "Transfer of asset " + number
                    + " to another cost centre: " + t.get("reason"), lines, (UUID) t.get("prepared_by_id"), (String) t.get("prepared_by"), false))
                    .entryId();
        }
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("UPDATE fixed_assets SET cost_center_id = ?, location = ?, custodian = ?, version = version + 1, updated_at = ?, updated_by = ? "
                + "WHERE id = ?", to, t.get("to_location"), t.get("to_custodian"), now, CurrentActor.username(), assetId);
        jdbc.update("UPDATE asset_transfers SET status = 'POSTED', posted_by = ?, posted_at = ?, posted_entry_id = ?, version = version + 1, "
                + "updated_at = ?, updated_by = ? WHERE id = ?", CurrentActor.username(), now, entryId, now, CurrentActor.username(), id);
        notifications.notify((UUID) t.get("prepared_by_id"), "DOCUMENT_POSTED", "Transfer of asset " + number + " was posted by "
                + CurrentActor.username(), null, "/fixed-assets/" + assetId);
        audit.record("ASSET_TRANSFERRED", "FIXED_ASSET", assetId, Map.of("transfer", id.toString(), "fromCostCenter", String.valueOf(from),
                "toCostCenter", String.valueOf(to), "toLocation", String.valueOf(t.get("to_location")), "toCustodian",
                String.valueOf(t.get("to_custodian")), "ledgerReclass", entryId != null));
        return view(id);
    }

    @Transactional
    public TransferView cancel(UUID id, long version) {
        Map<String, Object> t = lock(id, version);
        if (!CurrentActor.hasPermission("FA_MANAGE") && !CurrentActor.hasPermission("JOURNAL_POST")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot cancel asset transfers.");
        }
        jdbc.update("UPDATE asset_transfers SET status = 'CANCELLED', version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?",
                Timestamp.from(Instant.now(clock)), CurrentActor.username(), id);
        audit.record("ASSET_TRANSFER_CANCELLED", "FIXED_ASSET", t.get("asset_id"), Map.of("transfer", id.toString()));
        return view(id);
    }

    /** Moves a balance between cost centres on an account that carries them; nothing when the account does not or the amount is zero. */
    private void reclass(List<LineData> lines, UUID account, UUID from, UUID to, BigDecimal amount, boolean debitBalance, String number) {
        UUID ccFrom = assets.costCenterFor(account, from);
        UUID ccTo = assets.costCenterFor(account, to);
        if (Objects.equals(ccFrom, ccTo) || amount.signum() == 0) {
            return;
        }
        BigDecimal a = Money.of(amount);
        String text = "Transfer " + number;
        if (debitBalance) {
            lines.add(new LineData(account, ccTo, null, text, a, Money.ZERO));
            lines.add(new LineData(account, ccFrom, null, text, Money.ZERO, a));
        } else {
            lines.add(new LineData(account, ccFrom, null, text, a, Money.ZERO));
            lines.add(new LineData(account, ccTo, null, text, Money.ZERO, a));
        }
    }

    private Map<String, Object> lock(UUID id, long version) {
        Map<String, Object> t = jdbc.queryForList("SELECT * FROM asset_transfers WHERE id = ? FOR UPDATE", id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Asset transfer"));
        if (((Number) t.get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        if (!"DRAFT".equals(t.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The transfer is already " + t.get("status").toString().toLowerCase() + ".");
        }
        return t;
    }

    private TransferView view(UUID id) {
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT t.*, a.asset_number, f.code AS from_cc, c.code AS to_cc, e.entry_number FROM asset_transfers t
                JOIN fixed_assets a ON a.id = t.asset_id LEFT JOIN cost_centers f ON f.id = t.from_cost_center_id
                LEFT JOIN cost_centers c ON c.id = t.to_cost_center_id LEFT JOIN journal_entries e ON e.id = t.posted_entry_id WHERE t.id = ?""", id);
        String status = (String) t.get("status");
        List<String> actions = new ArrayList<>();
        if ("DRAFT".equals(status)) {
            if (!CurrentActor.userId().equals(t.get("prepared_by_id")) && CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("POST");
            }
            if (CurrentActor.hasPermission("FA_MANAGE") || CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("CANCEL");
            }
        }
        Timestamp posted = (Timestamp) t.get("posted_at");
        return new TransferView(id, (UUID) t.get("asset_id"), (String) t.get("asset_number"), ((Date) t.get("transfer_date")).toLocalDate(),
                (UUID) t.get("from_cost_center_id"), (String) t.get("from_cc"), (UUID) t.get("to_cost_center_id"), (String) t.get("to_cc"),
                (String) t.get("from_location"), (String) t.get("to_location"), (String) t.get("from_custodian"), (String) t.get("to_custodian"),
                (String) t.get("reason"), status, (String) t.get("prepared_by"), (String) t.get("posted_by"), posted == null ? null : posted.toInstant(),
                (String) t.get("entry_number"), actions, ((Number) t.get("version")).longValue());
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
