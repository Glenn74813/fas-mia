package com.miaa.cas.fixedassets;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.fixedassets.FaDtos.CategoryRequest;
import com.miaa.cas.fixedassets.FaDtos.CategoryView;
import com.miaa.cas.fixedassets.FaDtos.ProjectRequest;
import com.miaa.cas.fixedassets.FaDtos.ProjectView;
import com.miaa.cas.generalledger.domain.SubsidiaryAccount;
import com.miaa.cas.generalledger.domain.SubsidiaryAccountRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asset categories (which accounts an asset uses and its proposed useful life) and assets under
 * construction. Each AUC project owns an OTHER subsidiary account; its costs are whatever is posted to
 * the AUC account with that subsidiary, and capitalisation credits it.
 */
@Service
public class AssetSetupService {

    private final SubsidiaryAccountRepository subsidiaries;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AssetSetupService(SubsidiaryAccountRepository subsidiaries, AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.subsidiaries = subsidiaries;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> categories() {
        return jdbc.query(CATEGORY_SQL + " ORDER BY c.code", (rs, i) -> category(rs));
    }

    @Transactional(readOnly = true)
    public CategoryView category(UUID id) {
        List<CategoryView> rows = jdbc.query(CATEGORY_SQL + " WHERE c.id = ?", (rs, i) -> category(rs), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Asset category");
        }
        return rows.get(0);
    }

    @Transactional
    public CategoryView createCategory(CategoryRequest req, boolean placeholder, Actor actor) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM asset_categories WHERE code = ?)", Boolean.class,
                req.code()))) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Asset category code already exists.");
        }
        requireAccount(req.assetAccountId(), "ASSET", "DEBIT", "asset");
        requireAccount(req.accumulatedAccountId(), "ASSET", "CREDIT", "accumulated depreciation (a contra-asset)");
        requireAccount(req.expenseAccountId(), "EXPENSE", null, "depreciation expense");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO asset_categories (id, code, name, asset_account_id, accumulated_account_id, expense_account_id,
                    default_useful_life_months, default_residual_rate, status, placeholder, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)""", id, req.code(), req.name().trim(), req.assetAccountId(),
                req.accumulatedAccountId(), req.expenseAccountId(), req.defaultUsefulLifeMonths(), req.defaultResidualRate(),
                placeholder, Timestamp.from(Instant.now(clock)), actor.username());
        audit.record(actor, "ASSET_CATEGORY_CREATED", "ASSET_CATEGORY", req.code(), Map.of("lifeMonths", req.defaultUsefulLifeMonths(),
                "placeholder", placeholder));
        return category(id);
    }

    @Transactional(readOnly = true)
    public List<ProjectView> projects() {
        return jdbc.query(PROJECT_SQL + " ORDER BY p.code", (rs, i) -> project(rs));
    }

    @Transactional(readOnly = true)
    public ProjectView project(UUID id) {
        List<ProjectView> rows = jdbc.query(PROJECT_SQL + " WHERE p.id = ?", (rs, i) -> project(rs), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("AUC project");
        }
        return rows.get(0);
    }

    @Transactional
    public ProjectView createProject(ProjectRequest req, Actor actor) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM auc_projects WHERE code = ?)", Boolean.class,
                req.code())) || subsidiaries.existsBySlTypeAndCode("OTHER", req.code())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Project code already exists.");
        }
        Map<String, Object> a = requireAccount(req.aucAccountId(), "ASSET", "DEBIT", "construction-in-progress");
        if (!"OTHER".equals(a.get("subsidiary_type"))) {
            throw FixedAssetService.rule("Account " + a.get("code") + " must keep an OTHER subsidiary ledger so costs are tracked per project.");
        }
        Instant now = Instant.now(clock);
        SubsidiaryAccount sl = subsidiaries.saveAndFlush(new SubsidiaryAccount(UUID.randomUUID(), "OTHER", req.code(),
                "AUC " + req.name().trim(), now, actor.username()));
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO auc_projects (id, code, name, auc_account_id, subsidiary_account_id, cost_center_id, status, "
                + "created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)", id, req.code(), req.name().trim(),
                req.aucAccountId(), sl.getId(), req.costCenterId(), Timestamp.from(now), actor.username());
        audit.record(actor, "AUC_PROJECT_CREATED", "AUC_PROJECT", req.code(), Map.of("name", req.name().trim()));
        return project(id);
    }

    @Transactional
    public ProjectView closeProject(UUID id, long version, Actor actor) {
        ProjectView p = project(id);
        if (p.balance().signum() != 0) {
            throw FixedAssetService.rule("Project " + p.code() + " still carries " + p.balance().toPlainString()
                    + "; capitalise or reclassify it before closing.");
        }
        if (jdbc.update("UPDATE auc_projects SET status = 'CLOSED', version = version + 1 WHERE id = ? AND version = ?", id, version) == 0) {
            throw ApiException.staleVersion();
        }
        subsidiaries.findById(p.subsidiaryAccountId()).ifPresent(sl -> sl.update(sl.getName(), "INACTIVE"));
        audit.record(actor, "AUC_PROJECT_CLOSED", "AUC_PROJECT", p.code(), Map.of());
        return project(id);
    }

    Map<String, Object> requireAccount(UUID id, String type, String normal, String purpose) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT code, account_type, normal_balance, postable, status, "
                + "subsidiary_type FROM chart_of_accounts WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Account");
        }
        Map<String, Object> a = rows.get(0);
        boolean ok = type.equals(a.get("account_type")) && (normal == null || normal.equals(a.get("normal_balance")))
                && Boolean.TRUE.equals(a.get("postable")) && "ACTIVE".equals(a.get("status"));
        if (!ok) {
            throw FixedAssetService.rule("Account " + a.get("code") + " is not an active, postable " + purpose + " account.");
        }
        return a;
    }

    private static final String CATEGORY_SQL = """
            SELECT c.*, a.code AS a_code, d.code AS d_code, e.code AS e_code FROM asset_categories c
            JOIN chart_of_accounts a ON a.id = c.asset_account_id JOIN chart_of_accounts d ON d.id = c.accumulated_account_id
            JOIN chart_of_accounts e ON e.id = c.expense_account_id""";

    private static CategoryView category(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CategoryView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getObject("asset_account_id", UUID.class), rs.getString("a_code"), rs.getObject("accumulated_account_id", UUID.class),
                rs.getString("d_code"), rs.getObject("expense_account_id", UUID.class), rs.getString("e_code"),
                rs.getInt("default_useful_life_months"), rs.getBigDecimal("default_residual_rate"), rs.getString("status"),
                rs.getBoolean("placeholder"));
    }

    private static final String PROJECT_SQL = """
            SELECT p.*, a.code AS a_code, s.code AS s_code,
                   coalesce((SELECT sum(l.debit) FROM journal_entry_lines l WHERE l.account_id = p.auc_account_id
                             AND l.subsidiary_account_id = p.subsidiary_account_id), 0) AS incurred,
                   coalesce((SELECT sum(l.credit) FROM journal_entry_lines l WHERE l.account_id = p.auc_account_id
                             AND l.subsidiary_account_id = p.subsidiary_account_id), 0) AS capitalized
            FROM auc_projects p JOIN chart_of_accounts a ON a.id = p.auc_account_id
            JOIN subsidiary_ledger_accounts s ON s.id = p.subsidiary_account_id""";

    private static ProjectView project(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal incurred = Money.of(rs.getBigDecimal("incurred"));
        BigDecimal capitalized = Money.of(rs.getBigDecimal("capitalized"));
        return new ProjectView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getObject("auc_account_id", UUID.class), rs.getString("a_code"), rs.getObject("subsidiary_account_id", UUID.class),
                rs.getString("s_code"), rs.getObject("cost_center_id", UUID.class), rs.getString("status"), incurred, capitalized,
                Money.of(incurred.subtract(capitalized)), rs.getLong("version"));
    }
}
