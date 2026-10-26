package com.miaa.cas.migration;

import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The fixed-asset register at cutover: cost, residual value, useful life, the legacy accumulated depreciation and
 * the life still to run. The batch reconciles per category account (cost on the asset account, accumulated
 * depreciation on its contra account) with the opening entry. Migrated assets continue to depreciate from the
 * cutover month over their remaining life; their legacy depreciation is not recomputed (C-30).
 */
@Component
class AssetRules implements KindRules {

    record Asset(String number, UUID categoryId, String description, String serial, String location, String custodian,
            UUID costCenterId, LocalDate acquired, LocalDate inService, BigDecimal cost, BigDecimal residual, int life,
            int remaining, BigDecimal accumulated) {
    }

    private record Category(UUID id, boolean active, UUID assetAccount, String assetCode, String assetName, UUID accAccount,
            String accCode, String accName) {
    }

    private final JdbcTemplate jdbc;

    AssetRules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MigrationService.Kind kind() {
        return MigrationService.Kind.FA_REGISTER;
    }

    @Override
    public String basis() {
        return "asset cost and accumulated depreciation accounts";
    }

    @Override
    public Checked check(List<MigrationCsv.Row> rows, LocalDate cutover) {
        Map<String, Category> categories = new HashMap<>();
        jdbc.query("""
                SELECT c.id, c.code, c.status, a.id AS a_id, a.code AS a_code, a.name AS a_name, d.id AS d_id, d.code AS d_code, d.name AS d_name
                FROM asset_categories c JOIN chart_of_accounts a ON a.id = c.asset_account_id
                JOIN chart_of_accounts d ON d.id = c.accumulated_account_id""", rs -> {
            categories.put(rs.getString("code"), new Category(rs.getObject("id", UUID.class), "ACTIVE".equals(rs.getString("status")),
                    rs.getObject("a_id", UUID.class), rs.getString("a_code"), rs.getString("a_name"), rs.getObject("d_id", UUID.class),
                    rs.getString("d_code"), rs.getString("d_name")));
        });
        Map<String, UUID> costCenters = new HashMap<>();
        jdbc.query("SELECT id, code FROM cost_centers WHERE status = 'ACTIVE'", rs -> {
            costCenters.put(rs.getString("code"), rs.getObject("id", UUID.class));
        });
        Object numbers = rows.stream().map(r -> r.values().get("asset_number").toLowerCase(Locale.ROOT)).distinct().toArray(String[]::new);
        Set<String> existing = new HashSet<>(jdbc.queryForList(
                "SELECT lower(asset_number) FROM fixed_assets WHERE lower(asset_number) = ANY(?)", String.class, numbers));

        List<List<String>> rowErrors = new ArrayList<>();
        List<Asset> items = new ArrayList<>();
        Map<String, Side> batch = KindRules.sides();
        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (MigrationCsv.Row r : rows) {
            List<String> e = new ArrayList<>();
            Map<String, String> v = r.values();
            String number = KindRules.text(v.get("asset_number"), "asset_number", e, true, 30);
            if (number != null && !seen.add(number.toLowerCase(Locale.ROOT))) {
                e.add("asset_number " + number + " appears more than once in the file.");
            } else if (number != null && existing.contains(number.toLowerCase(Locale.ROOT))) {
                e.add("asset_number " + number + " already exists in CAS.");
            }
            Category cat = categories.get(v.get("category_code"));
            if (v.get("category_code").isEmpty()) {
                e.add("category_code is required.");
            } else if (cat == null) {
                e.add("Asset category " + v.get("category_code") + " does not exist.");
            } else if (!cat.active()) {
                e.add("Asset category " + v.get("category_code") + " is inactive.");
            }
            UUID cc = null;
            if (!v.get("cost_center_code").isEmpty()) {
                cc = costCenters.get(v.get("cost_center_code"));
                if (cc == null) {
                    e.add("No active cost centre " + v.get("cost_center_code") + ".");
                }
            }
            String description = KindRules.text(v.get("description"), "description", e, true, 500);
            String serial = KindRules.text(v.get("serial_number"), "serial_number", e, false, 100);
            String location = KindRules.text(v.get("location"), "location", e, false, 200);
            String custodian = KindRules.text(v.get("custodian"), "custodian", e, false, 200);
            LocalDate acquired = KindRules.date(v.get("acquisition_date"), "acquisition_date", e);
            LocalDate inService = KindRules.date(v.get("in_service_date"), "in_service_date", e);
            if (acquired != null && inService != null && inService.isBefore(acquired)) {
                e.add("in_service_date is before acquisition_date.");
            }
            if (inService != null && !inService.isBefore(cutover)) {
                e.add("in_service_date must be before the cutover date " + cutover + "; later assets are registered in CAS.");
            }
            BigDecimal cost = KindRules.amount(v.get("cost"), "cost", e, false);
            BigDecimal residual = KindRules.amount(v.get("residual_value"), "residual_value", e, true);
            BigDecimal acc = KindRules.amount(v.get("accumulated_depreciation"), "accumulated_depreciation", e, false);
            Integer life = KindRules.integer(v.get("useful_life_months"), "useful_life_months", e, 1, 1200);
            Integer remaining = KindRules.integer(v.get("remaining_life_months"), "remaining_life_months", e, 0, 1200);
            residual = residual == null ? BigDecimal.ZERO.setScale(2) : residual;
            if (cost != null && cost.signum() == 0) {
                e.add("cost must be greater than zero.");
            }
            if (cost != null && residual.compareTo(cost) >= 0) {
                e.add("residual_value must be less than cost.");
            }
            if (cost != null && acc != null && acc.compareTo(cost.subtract(residual)) > 0) {
                e.add("accumulated_depreciation exceeds cost less residual value.");
            }
            if (life != null && remaining != null && remaining > life) {
                e.add("remaining_life_months exceeds useful_life_months.");
            }
            if (cost != null && acc != null && remaining != null && (remaining == 0) != (acc.compareTo(cost.subtract(residual)) == 0)) {
                e.add(remaining == 0 ? "remaining_life_months is 0 but the asset is not fully depreciated."
                        : "The asset is fully depreciated; remaining_life_months must be 0.");
            }
            if (e.isEmpty()) {
                total = total.add(cost);
                items.add(new Asset(number, cat.id(), description, serial, location, custodian, cc, acquired, inService, cost, residual,
                        life, remaining, acc));
                KindRules.add(batch, "C|" + cat.assetAccount(), cat.assetCode(), cat.assetName(), cost);
                KindRules.add(batch, "A|" + cat.accAccount(), cat.accCode(), cat.accName(), acc);
            }
            rowErrors.add(e);
        }
        return new Checked(rowErrors, new ArrayList<>(), total, batch, items, List.of());
    }

    @Override
    public Map<String, Side> ledger(Entry entry) {
        Map<String, Side> sides = KindRules.sides();
        jdbc.query("""
                SELECT a.id, a.code, a.name, 'C' AS side, coalesce(sum(l.debit - l.credit), 0) AS amt
                FROM chart_of_accounts a LEFT JOIN journal_entry_lines l ON l.account_id = a.id AND l.entry_id = ?
                WHERE a.id IN (SELECT asset_account_id FROM asset_categories) GROUP BY a.id, a.code, a.name
                UNION ALL
                SELECT a.id, a.code, a.name, 'A', coalesce(sum(l.credit - l.debit), 0)
                FROM chart_of_accounts a LEFT JOIN journal_entry_lines l ON l.account_id = a.id AND l.entry_id = ?
                WHERE a.id IN (SELECT accumulated_account_id FROM asset_categories) GROUP BY a.id, a.code, a.name""", rs -> {
            KindRules.add(sides, rs.getString("side") + "|" + rs.getObject("id"), rs.getString("code"), rs.getString("name"),
                    rs.getBigDecimal("amt"));
        }, entry.id(), entry.id());
        return sides;
    }

    @Override
    public void apply(Context ctx, Checked checked) {
        List<Object[]> params = new ArrayList<>();
        for (Object o : checked.items()) {
            Asset a = (Asset) o;
            params.add(new Object[] {UUID.randomUUID(), a.number(), a.categoryId(), a.description(), a.serial(), a.location(), a.custodian(),
                    a.costCenterId(), Date.valueOf(a.acquired()), Date.valueOf(a.inService()), a.cost(), a.residual(), a.life(),
                    a.remaining() == 0 ? "FULLY_DEPRECIATED" : "ACTIVE", ctx.preparerId(), ctx.preparer(), CurrentActor.username(), ctx.now(),
                    ctx.entry().id(), ctx.now(), ctx.now(), CurrentActor.username(), a.accumulated(), Date.valueOf(ctx.entry().date()),
                    a.remaining(), ctx.batchId()});
        }
        jdbc.batchUpdate("""
                INSERT INTO fixed_assets (id, asset_number, category_id, description, serial_number, location, custodian, cost_center_id,
                    acquisition_source, acquisition_date, in_service_date, cost, residual_value, useful_life_months, status, prepared_by_id,
                    prepared_by, activated_by, activated_at, acquisition_entry_id, created_at, updated_at, updated_by, opening_accumulated,
                    depreciation_basis_date, remaining_life_months, migration_batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MIGRATION', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", params);
    }
}
