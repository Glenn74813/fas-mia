package com.miaa.cas.config.dev;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.financialreporting.ReportDtos.TemplateLineRequest;
import com.miaa.cas.financialreporting.ReportDtos.TemplateRequest;
import com.miaa.cas.financialreporting.StatementTemplateService;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.generalledger.AccountService;
import com.miaa.cas.generalledger.domain.Account;
import com.miaa.cas.generalledger.domain.AccountRepository;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.LedgerBalanceService;
import com.miaa.cas.generalledger.LedgerBalanceService.Movement;
import com.miaa.cas.generalledger.domain.AccountType;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.journal.DecisionLog;
import com.miaa.cas.journal.NumberingService;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.SortedMap;
import java.util.TreeMap;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT ONLY. Moves a development database off the placeholder chart onto the Revised Chart of Accounts for
 * Government Corporations (2019), COA Circulars 2020-002 and 2021-005: loads the official accounts, points the CAS
 * settings (account mappings, tax codes, bank accounts, asset categories, stock items, contracts, recurring and
 * allocation templates) at them, files the placeholder accounts' past balances under the official statement lines,
 * replaces the placeholder statement templates and deactivates the placeholder accounts. Posted entries are not
 * touched; they stay on the placeholder accounts. Idempotent, so it runs on every dev start.
 *
 * <p>The account codes and titles are COA's. The statement-line groupings, the cost-centre and budget flags and the
 * statement layouts are proposals MIAA's Accounting Department confirms (C-07, C-22).
 */
@Component
@Profile("dev")
public class DevOfficialChart {

    /** Cash on hand of collecting officers: present once the official chart is loaded. */
    static final String MARKER = "10101010";

    private static final Logger log = LoggerFactory.getLogger(DevOfficialChart.class);

    /** Columns that hold a setting (not a posted record) and so follow the placeholder account to its official one. */
    private static final List<String> SETTINGS = List.of("tax_codes.account_id", "bank_accounts.gl_account_id",
            "asset_categories.asset_account_id", "asset_categories.expense_account_id", "inventory_items.inventory_account_id",
            "inventory_items.consumption_account_id", "customer_contracts.revenue_account_id", "recurring_journal_lines.account_id",
            "allocation_rules.sender_account_id", "allocation_rules.receiver_account_id", "auc_projects.auc_account_id");

    /** Account mappings whose meaning differs from the placeholder account they shared. */
    private static final Map<String, String> MAPPING_OVERRIDES = Map.of("INVENTORY_REVALUATION", "50503070");

    /** Placeholder statement line codes to the official line they fall under. */
    private static final Map<String, String> LINE_CODES = Map.of("PLC-SFP-CASH", "RCA-SFP-CASH", "PLC-SFP-REC", "RCA-SFP-REC",
            "PLC-SFP-INV", "RCA-SFP-INV", "PLC-SFP-PPE", "RCA-SFP-PPE", "PLC-SFP-PAY", "RCA-SFP-FL", "PLC-SFP-TAX", "RCA-SFP-IAP",
            "PLC-SFP-DEF", "RCA-SFP-DEF", "PLC-SFP-EQ", "RCA-SFP-GE", "PLC-SFPERF-REV", "RCA-SCI-BSI", "PLC-SFPERF-EXP", "RCA-SCI-MOOE");

    static final String CHANGEOVER_SOURCE = "SL_CHART_CHANGEOVER";
    static final String CHANGEOVER_REFERENCE = "RCA-GC-2019";
    private static final String CONVERSION_USER = "system.conversion";

    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final AccountMappingService mappings;
    private final StatementTemplateService templates;
    private final AuditService audit;
    private final FiscalCalendarService calendar;
    private final NumberingService numbering;
    private final LedgerBalanceService balances;
    private final DecisionLog decisions;
    private final Clock clock;

    public DevOfficialChart(JdbcTemplate jdbc, AccountRepository accounts, AccountService accountService, AccountMappingService mappings,
            StatementTemplateService templates, AuditService audit, FiscalCalendarService calendar, NumberingService numbering,
            LedgerBalanceService balances, DecisionLog decisions, Clock clock) {
        this.calendar = calendar;
        this.numbering = numbering;
        this.balances = balances;
        this.decisions = decisions;
        this.clock = clock;
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.accountService = accountService;
        this.mappings = mappings;
        this.templates = templates;
        this.audit = audit;
    }

    boolean loaded() {
        return accounts.existsByCode(MARKER);
    }

    void apply() {
        if (!loaded()) {
            try (InputStream in = new ClassPathResource("seed/coa-rca-gc-2019.csv").getInputStream()) {
                var result = accountService.importCsv(in, false, Actor.SYSTEM);
                log.warn("DEV: loaded {} accounts of the Revised Chart of Accounts for Government Corporations (2019)", result.created());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        syncSubsidiaryTypes();
        Map<UUID, Account> moves = moves();
        repointMappings(moves);
        repointSettings(moves);
        postChangeover(moves);
        replaceTemplates();
        deactivatePlaceholders();
        // After deactivating: the account service saves whole entities, which would write back the old line codes.
        refileStatementLines(moves);
        deleteUnusedPlaceholders();
    }

    /**
     * The chart file's sub-ledger types also reach accounts loaded from an earlier version of it, as long as nothing
     * has been posted to them yet (a used account keeps its sub-ledger as it is).
     */
    private void syncSubsidiaryTypes() {
        try (InputStream in = new ClassPathResource("seed/coa-rca-gc-2019.csv").getInputStream()) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#") && !l.startsWith("code,")).toList()) {
                String[] r = AccountService.splitCsvLine(line);
                String sl = r[8].isBlank() ? null : r[8].trim();
                int n = jdbc.update("""
                        UPDATE chart_of_accounts c SET subsidiary_type = ?, version = version + 1, updated_at = now(), updated_by = 'system'
                        WHERE c.code = ? AND c.subsidiary_type IS DISTINCT FROM ?
                          AND NOT EXISTS (SELECT 1 FROM journal_entry_lines l WHERE l.account_id = c.id)
                          AND NOT EXISTS (SELECT 1 FROM journal_voucher_lines l WHERE l.account_id = c.id)""", sl, r[0].trim(), sl);
                if (n > 0) {
                    audit.record(Actor.SYSTEM, "ACCOUNT_UPDATED", "ACCOUNT", r[0].trim(), Map.of("subsidiaryType", String.valueOf(sl),
                            "reason", "Sub-ledger type from the development chart file"));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Placeholder account id to its official account, from seed/plc-to-rca-gc-2019.csv. */
    private Map<UUID, Account> moves() {
        Map<UUID, Account> out = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource("seed/plc-to-rca-gc-2019.csv").getInputStream()) {
            new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank() && !l.startsWith("#")).skip(1)
                    .map(l -> l.split(",", 4)).forEach(r -> accounts.findByCode(r[0]).ifPresent(plc ->
                            out.put(plc.getId(), accounts.findByCode(r[1]).orElseThrow(() -> new IllegalStateException(
                                    "Official account " + r[1] + " for " + r[0] + " is not in the chart")))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private void repointMappings(Map<UUID, Account> moves) {
        for (var m : mappings.list()) {
            Account to = MAPPING_OVERRIDES.containsKey(m.key()) ? accounts.findByCode(MAPPING_OVERRIDES.get(m.key())).orElseThrow()
                    : moves.get(m.accountId());
            if (to != null && !to.getId().equals(m.accountId())) {
                mappings.set(m.key(), to.getId(), Actor.SYSTEM);
            }
        }
    }

    private void repointSettings(Map<UUID, Account> moves) {
        for (String column : SETTINGS) {
            String table = column.substring(0, column.indexOf('.'));
            String col = column.substring(column.indexOf('.') + 1);
            int changed = 0;
            for (var e : moves.entrySet()) {
                changed += jdbc.update("UPDATE " + table + " SET " + col + " = ? WHERE " + col + " = ?", e.getValue().getId(), e.getKey());
            }
            if (changed > 0) {
                audit.record(Actor.SYSTEM, "SETTINGS_ACCOUNTS_REPOINTED", "CHART_OF_ACCOUNTS", column, Map.of("rows", changed,
                        "chart", "RCA-GC-2019"));
            }
        }
        // Accumulated depreciation is kept per asset class in the official chart: the asset account's code + 1.
        int categories = jdbc.update("""
                UPDATE asset_categories c SET accumulated_account_id = acc.id
                FROM chart_of_accounts asset, chart_of_accounts acc
                WHERE asset.id = c.asset_account_id AND asset.code ~ '^[0-9]{8}$'
                  AND acc.code = lpad((asset.code::bigint + 1)::text, 8, '0') AND c.accumulated_account_id <> acc.id""");
        if (categories > 0) {
            audit.record(Actor.SYSTEM, "SETTINGS_ACCOUNTS_REPOINTED", "CHART_OF_ACCOUNTS", "asset_categories.accumulated_account_id",
                    Map.of("rows", categories, "chart", "RCA-GC-2019"));
        }
    }

    /**
     * One conversion entry, dated today, moves every balance-sheet balance on a placeholder account to its official
     * account (per cost centre and sub-ledger account), so control-account reconciliations and the statement of financial
     * position read from the official chart. Income and expense stay where they were posted: the year's actuals and the
     * budget they were checked against remain together. Earlier entries are not touched.
     *
     * <p>Development only: the entry is written by the seeder as user {@code system.conversion}, a service user that cannot
     * sign in, outside the voucher approval workflow (no person prepares or approves it). The audit trail says so.
     */
    private void postChangeover(Map<UUID, Account> moves) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM journal_entries WHERE source_type = ? AND source_reference = ?)",
                Boolean.class, CHANGEOVER_SOURCE, CHANGEOVER_REFERENCE))) {
            return;
        }
        record Line(UUID account, UUID costCenter, UUID subsidiary, BigDecimal debit, BigDecimal credit, String description) {
        }
        List<Line> lines = new java.util.ArrayList<>();
        for (var e : moves.entrySet()) {
            Account to = e.getValue();
            if (!java.util.Set.of(AccountType.ASSET, AccountType.LIABILITY, AccountType.EQUITY).contains(to.getAccountType())) {
                continue;
            }
            String plcCode = jdbc.queryForObject("SELECT code FROM chart_of_accounts WHERE id = ?", String.class, e.getKey());
            for (Map<String, Object> b : jdbc.queryForList("""
                    SELECT l.cost_center_id, l.subsidiary_account_id, s.sl_type, sum(l.debit - l.credit) AS net
                    FROM journal_entry_lines l LEFT JOIN subsidiary_ledger_accounts s ON s.id = l.subsidiary_account_id
                    WHERE l.account_id = ? GROUP BY 1, 2, 3 HAVING sum(l.debit - l.credit) <> 0""", e.getKey())) {
                BigDecimal net = (BigDecimal) b.get("net");
                UUID cc = (UUID) b.get("cost_center_id");
                UUID sl = (UUID) b.get("subsidiary_account_id");
                // The sub-ledger detail follows only where the official account keeps the same kind of sub-ledger.
                UUID toSl = sl != null && java.util.Objects.equals(b.get("sl_type"), to.getSubsidiaryType()) ? sl : null;
                String text = "Changeover " + plcCode + " to " + to.getCode();
                BigDecimal abs = net.abs();
                boolean debitBalance = net.signum() > 0;
                lines.add(new Line(to.getId(), cc, toSl, debitBalance ? abs : BigDecimal.ZERO, debitBalance ? BigDecimal.ZERO : abs, text));
                lines.add(new Line(e.getKey(), cc, sl, debitBalance ? BigDecimal.ZERO : abs, debitBalance ? abs : BigDecimal.ZERO, text));
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        UUID user = conversionUser();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Manila")));
        AccountingPeriod period = calendar.resolve(today, false);
        BigDecimal total = lines.stream().map(Line::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        String description = "Changeover from the development placeholder chart to the Revised Chart of Accounts for "
                + "Government Corporations (2019): balance-sheet balances moved to the official accounts";
        String number = numbering.next(FiscalCalendarService.JEV_DOCUMENT, period.getSequenceKey() / 100);
        UUID voucherId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO journal_vouchers (id, voucher_number, voucher_type, status, accounting_date, period_id, description,
                    supporting_reference, source_type, source_reference, total_debit, total_credit, prepared_by_id, prepared_by,
                    created_at, updated_at, updated_by)
                VALUES (?, ?, 'SUBLEDGER', 'POSTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", voucherId, number, Date.valueOf(today),
                period.getId(), description, CHANGEOVER_REFERENCE, CHANGEOVER_SOURCE, CHANGEOVER_REFERENCE, total, total, user,
                CONVERSION_USER, now, now, CONVERSION_USER);
        jdbc.update("""
                INSERT INTO journal_entries (id, entry_number, voucher_id, voucher_type, fiscal_year_id, period_id, accounting_date,
                    description, source_type, source_reference, currency_code, total_debit, total_credit, prepared_by, posted_by_id,
                    posted_by, posted_at)
                VALUES (?, ?, ?, 'SUBLEDGER', ?, ?, ?, ?, ?, ?, 'PHP', ?, ?, ?, ?, ?, ?)""", entryId, number, voucherId,
                period.getFiscalYearId(), period.getId(), Date.valueOf(today), description, CHANGEOVER_SOURCE, CHANGEOVER_REFERENCE,
                total, total, CONVERSION_USER, user, CONVERSION_USER, now);
        SortedMap<UUID, Movement> movements = new TreeMap<>();
        int n = 1;
        for (Line l : lines) {
            jdbc.update("""
                    INSERT INTO journal_voucher_lines (id, voucher_id, line_no, account_id, cost_center_id, subsidiary_account_id,
                        description, debit, credit) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), voucherId, n, l.account(),
                    l.costCenter(), l.subsidiary(), l.description(), l.debit(), l.credit());
            jdbc.update("""
                    INSERT INTO journal_entry_lines (id, entry_id, line_no, account_id, cost_center_id, subsidiary_account_id,
                        description, debit, credit, period_id, accounting_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), entryId, n++, l.account(), l.costCenter(), l.subsidiary(), l.description(), l.debit(),
                    l.credit(), period.getId(), Date.valueOf(today));
            movements.merge(l.account(), new Movement(l.debit(), l.credit()),
                    (a, b) -> new Movement(a.debit().add(b.debit()), a.credit().add(b.credit())));
        }
        jdbc.update("UPDATE journal_vouchers SET posted_entry_id = ? WHERE id = ?", entryId, voucherId);
        balances.apply(period.getId(), movements);
        decisions.record(voucherId, "POST", null, "POST", user, CONVERSION_USER, "Development chart changeover; no approval workflow");
        audit.record(Actor.SYSTEM, "JOURNAL_VOUCHER_POSTED", "JOURNAL_VOUCHER", voucherId, Map.of("entryNumber", number,
                "entryId", entryId.toString(), "total", total.toPlainString(), "period", period.getName(),
                "source", CHANGEOVER_SOURCE + ":" + CHANGEOVER_REFERENCE,
                "note", "Development database conversion written by the seeder outside the approval workflow"));
        log.warn("DEV: posted changeover entry {} moving {} placeholder balances to the official chart", number, lines.size() / 2);
    }

    /** The service user conversion entries are attributed to. It is INACTIVE and has no usable password. */
    private UUID conversionUser() {
        List<UUID> found = jdbc.queryForList("SELECT id FROM users WHERE username = ?", UUID.class, CONVERSION_USER);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, email, display_name, user_type, status, password_hash, password_changed_at, created_by,
                    updated_by) VALUES (?, ?, 'system.conversion@invalid', 'System (development chart conversion)', 'INTERNAL', 'INACTIVE',
                    '!no-sign-in', now(), 'system', 'system')""", id, CONVERSION_USER);
        audit.record(Actor.SYSTEM, "USER_CREATED", "USER", CONVERSION_USER, Map.of("status", "INACTIVE",
                "reason", "Service user for the development chart changeover entry; cannot sign in"));
        return id;
    }

    /** Past placeholder balances appear under the statement line of the official account that replaces them. */
    private void refileStatementLines(Map<UUID, Account> moves) {
        int changed = 0;
        for (var e : moves.entrySet()) {
            changed += jdbc.update("UPDATE chart_of_accounts SET fs_line_code = ? WHERE id = ? AND fs_line_code IS DISTINCT FROM ?",
                    e.getValue().getFsLineCode(), e.getKey(), e.getValue().getFsLineCode());
        }
        // Accounts added during development on placeholder line codes follow the nearest official line.
        for (var e : LINE_CODES.entrySet()) {
            changed += jdbc.update("UPDATE chart_of_accounts SET fs_line_code = ? WHERE fs_line_code = ?", e.getValue(), e.getKey());
        }
        if (changed > 0) {
            audit.record(Actor.SYSTEM, "PLACEHOLDER_ACCOUNTS_REFILED", "CHART_OF_ACCOUNTS", "RCA-GC-2019", Map.of("accounts", changed));
        }
    }

    private void replaceTemplates() {
        for (TemplateRequest t : officialTemplates()) {
            if (Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM report_templates WHERE code = ?)", Boolean.class, t.code()))) {
                templates.activate(templates.create(t, false, Actor.SYSTEM).id(), Actor.SYSTEM);
                log.warn("DEV: created statement template {} over the official chart", t.code());
            }
        }
        int retired = jdbc.update("UPDATE report_templates SET status = 'RETIRED' WHERE code LIKE 'PLC-%' AND status = 'ACTIVE'");
        if (retired > 0) {
            audit.record(Actor.SYSTEM, "REPORT_TEMPLATES_RETIRED", "REPORT_TEMPLATE", "PLC-*", Map.of("templates", retired,
                    "replacedBy", "RCA-*"));
        }
    }

    private void deactivatePlaceholders() {
        int done = 0;
        List<Account> all = accounts.findAllByOrderByCodeAsc();
        Set<UUID> placeholders = all.stream().filter(Account::isPlaceholder).map(Account::getId).collect(Collectors.toSet());
        for (Account a : all) {
            // Accounts added under a placeholder heading during development (end-to-end test bank accounts) go with it.
            boolean retire = a.isPlaceholder() || placeholders.contains(a.getParentId());
            if (retire && "ACTIVE".equals(a.getStatus())) {
                Long unposted = jdbc.queryForObject("""
                        SELECT count(*) FROM journal_voucher_lines l JOIN journal_vouchers v ON v.id = l.voucher_id
                        WHERE l.account_id = ? AND v.status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'RETURNED')""", Long.class, a.getId());
                if (unposted == null || unposted == 0) {
                    accountService.setStatus(a.getId(), a.getVersion(), false);
                } else {
                    // Development leftovers (mostly end-to-end test vouchers) still name the account. Deactivate it anyway:
                    // posting refuses inactive accounts, so those vouchers are returned and corrected rather than posted.
                    jdbc.update("UPDATE chart_of_accounts SET status = 'INACTIVE', version = version + 1, updated_at = now(), "
                            + "updated_by = 'system' WHERE id = ?", a.getId());
                    audit.record(Actor.SYSTEM, "ACCOUNT_DEACTIVATED", "ACCOUNT", a.getCode(), Map.of("unpostedVoucherLines", unposted,
                            "reason", "Development database moved to the official chart"));
                }
                done++;
            }
        }
        if (done > 0) {
            log.warn("DEV: deactivated {} placeholder accounts; their posted history stays", done);
        }
        // Bank accounts whose ledger account is now inactive (end-to-end test banks) cannot be paid from or deposited to.
        for (Map<String, Object> b : jdbc.queryForList("""
                SELECT b.id, b.code FROM bank_accounts b JOIN chart_of_accounts a ON a.id = b.gl_account_id
                WHERE b.status = 'ACTIVE' AND a.status = 'INACTIVE'""")) {
            jdbc.update("UPDATE bank_accounts SET status = 'INACTIVE' WHERE id = ?", b.get("id"));
            audit.record(Actor.SYSTEM, "BANK_ACCOUNT_DEACTIVATED", "BANK_ACCOUNT", b.get("code"), Map.of("reason",
                    "Its ledger account was retired when the development database moved to the official chart"));
        }
    }

    /**
     * Placeholder accounts nothing refers to (no postings, budgets, settings or child accounts) are removed outright,
     * leaves first. Those with history stay, inactive, because posted records keep their accounts.
     */
    private void deleteUnusedPlaceholders() {
        List<String> refs = jdbc.queryForList("""
                SELECT kcu.table_name || '.' || kcu.column_name FROM information_schema.referential_constraints rc
                JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = rc.constraint_name
                JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = rc.unique_constraint_name
                WHERE ccu.table_name = 'chart_of_accounts' AND ccu.column_name = 'id'""", String.class);
        String unused = refs.stream().map(r -> "NOT EXISTS (SELECT 1 FROM " + r.substring(0, r.indexOf('.')) + " x WHERE x."
                + r.substring(r.indexOf('.') + 1) + " = c.id)").collect(Collectors.joining(" AND "));
        int deleted = 0;
        List<Map<String, Object>> batch;
        do {
            batch = jdbc.queryForList("SELECT id, code, name FROM chart_of_accounts c WHERE c.placeholder AND c.status = 'INACTIVE' AND "
                    + unused);
            for (Map<String, Object> row : batch) {
                jdbc.update("DELETE FROM chart_of_accounts WHERE id = ?", row.get("id"));
                audit.record(Actor.SYSTEM, "ACCOUNT_DELETED", "ACCOUNT", row.get("code"), Map.of("name", row.get("name"),
                        "reason", "Unused placeholder account; development database moved to the official chart"));
            }
            deleted += batch.size();
        } while (!batch.isEmpty());
        if (deleted > 0) {
            log.warn("DEV: deleted {} unused placeholder accounts", deleted);
        }
    }

    /** Statement layouts over the official chart's line codes. Proposed groupings until MIAA approves its formats (C-07). */
    public static List<TemplateRequest> officialTemplates() {
        return List.of(
                new TemplateRequest("RCA-SFP", "Statement of Financial Position", "SFP", "TA=TLE", List.of(
                        l("H-A", "Assets", "HEADER", List.of(), List.of(), 1, 0),
                        l("CASH", "Cash and cash equivalents", "LINE", sfp("CASH"), List.of(), 1, 1),
                        l("FIN", "Financial assets", "LINE", sfp("FIN"), List.of(), 1, 1),
                        l("REC", "Receivables", "LINE", sfp("REC"), List.of(), 1, 1),
                        l("INV", "Inventories", "LINE", sfp("INV"), List.of(), 1, 1),
                        l("OCA", "Prepayments, advances and deposits", "LINE", sfp("OCA"), List.of(), 1, 1),
                        l("IP", "Investment property", "LINE", sfp("IP"), List.of(), 1, 1),
                        l("PPE", "Property, plant and equipment", "LINE", sfp("PPE"), List.of(), 1, 1),
                        l("ROU", "Right-of-use assets", "LINE", sfp("ROU"), List.of(), 1, 1),
                        l("INT", "Intangible assets", "LINE", sfp("INT"), List.of(), 1, 1),
                        l("DTA", "Deferred tax assets", "LINE", sfp("DTA"), List.of(), 1, 1),
                        l("ONCA", "Biological and other assets", "LINE", sfp("ONCA"), List.of(), 1, 1),
                        l("TA", "Total assets", "TOTAL", List.of(), List.of("CASH", "FIN", "REC", "INV", "OCA", "IP", "PPE", "ROU", "INT",
                                "DTA", "ONCA"), 1, 0),
                        l("H-L", "Liabilities", "HEADER", List.of(), List.of(), 1, 0),
                        l("FL", "Financial liabilities", "LINE", sfp("FL"), List.of(), -1, 1),
                        l("LEASE", "Lease liabilities", "LINE", sfp("LEASE"), List.of(), -1, 1),
                        l("IAP", "Inter-agency payables", "LINE", sfp("IAP"), List.of(), -1, 1),
                        l("TRUST", "Trust liabilities", "LINE", sfp("TRUST"), List.of(), -1, 1),
                        l("DEF", "Deferred credits and unearned income", "LINE", sfp("DEF"), List.of(), -1, 1),
                        l("PROV", "Provisions", "LINE", sfp("PROV"), List.of(), -1, 1),
                        l("DTL", "Deferred tax liabilities", "LINE", sfp("DTL"), List.of(), -1, 1),
                        l("OP", "Other payables", "LINE", sfp("OP"), List.of(), -1, 1),
                        l("TL", "Total liabilities", "TOTAL", List.of(), List.of("FL", "LEASE", "IAP", "TRUST", "DEF", "PROV", "DTL", "OP"), 1, 0),
                        l("H-E", "Equity", "HEADER", List.of(), List.of(), 1, 0),
                        l("GE", "Government equity", "LINE", sfp("GE"), List.of(), -1, 1),
                        l("RE", "Retained earnings (deficit)", "LINE", sfp("RE"), List.of(), -1, 1),
                        l("OE", "Other equity", "LINE", sfp("OE"), List.of(), -1, 1),
                        l("SUR", "Profit (loss) for the year not yet closed", "SURPLUS", List.of(), List.of(), -1, 1),
                        l("TE", "Total equity", "TOTAL", List.of(), List.of("GE", "RE", "OE", "SUR"), 1, 0),
                        l("TLE", "Total liabilities and equity", "TOTAL", List.of(), List.of("TL", "TE"), 1, 0))),
                new TemplateRequest("RCA-SCI", "Statement of Comprehensive Income", "SFPERF", null, List.of(
                        l("H-I", "Income", "HEADER", List.of(), List.of(), 1, 0),
                        l("BSI", "Service and business income", "LINE", sci("BSI"), List.of(), -1, 1),
                        l("SDG", "Shares, grants and donations", "LINE", sci("SDG"), List.of(), -1, 1),
                        l("GAIN", "Gains", "LINE", sci("GAIN"), List.of(), -1, 1),
                        l("ONOI", "Other non-operating income", "LINE", sci("ONOI"), List.of(), -1, 1),
                        l("TI", "Total income", "TOTAL", List.of(), List.of("BSI", "SDG", "GAIN", "ONOI"), 1, 0),
                        l("H-X", "Expenses", "HEADER", List.of(), List.of(), 1, 0),
                        l("PS", "Personnel services", "LINE", sci("PS"), List.of(), 1, 1),
                        l("MOOE", "Maintenance and other operating expenses", "LINE", sci("MOOE"), List.of(), 1, 1),
                        l("FINX", "Financial expenses", "LINE", sci("FIN"), List.of(), 1, 1),
                        l("DC", "Direct costs", "LINE", sci("DC"), List.of(), 1, 1),
                        l("NC", "Non-cash expenses", "LINE", sci("NC"), List.of(), 1, 1),
                        l("TX", "Total expenses", "TOTAL", List.of(), List.of("PS", "MOOE", "FINX", "DC", "NC"), 1, 0),
                        l("PBT", "Profit (loss) before tax", "TOTAL", List.of(), List.of("TI", "-TX"), 1, 0),
                        l("TAX", "Income tax expense", "LINE", sci("TAX"), List.of(), 1, 1),
                        l("PAT", "Profit (loss) after tax", "TOTAL", List.of(), List.of("PBT", "-TAX"), 1, 0),
                        l("AS", "Assistance and subsidy", "LINE", sci("AS"), List.of(), -1, 1),
                        l("SUB", "Subsidy to other funds", "LINE", sci("SUB"), List.of(), 1, 1),
                        l("NET", "Net income (loss)", "TOTAL", List.of(), List.of("PAT", "AS", "-SUB"), 1, 0))),
                // Direct method: the change in cash classified by what each entry's other lines are.
                new TemplateRequest("RCA-SCF", "Statement of Cash Flows", "SCF", "CHK=CLOSE", List.of(
                        l("OPEN", "Cash and cash equivalents at the beginning of the year", "OPENING", sfp("CASH"), List.of(), 1, 0),
                        l("H-OP", "Cash flows from operating activities", "HEADER", List.of(), List.of(), 1, 0),
                        m("COLL", "Collections from customers and other income", List.of(sfp1("REC"), sci1("BSI"), sci1("AS"), sci1("SDG"),
                                sci1("ONOI"), sfp1("DEF"), sfp1("TRUST"))),
                        m("PAYS", "Payments to suppliers, employees and for expenses", List.of(sfp1("FL"), sfp1("OP"), sfp1("INV"),
                                sfp1("OCA"), sfp1("PROV"), sci1("PS"), sci1("MOOE"), sci1("FIN"), sci1("DC"), sci1("SUB"))),
                        m("REMIT", "Remittances of taxes and contributions", List.of(sfp1("IAP"), sci1("TAX"))),
                        l("NOP", "Net cash from operating activities", "TOTAL", List.of(), List.of("COLL", "PAYS", "REMIT"), 1, 0),
                        l("H-INV", "Cash flows from investing activities", "HEADER", List.of(), List.of(), 1, 0),
                        m("INVA", "Purchase and disposal of assets and investments", List.of(sfp1("PPE"), sfp1("IP"), sfp1("INT"),
                                sfp1("FIN"), sfp1("ONCA"), sci1("GAIN"))),
                        l("H-FIN", "Cash flows from financing activities", "HEADER", List.of(), List.of(), 1, 0),
                        m("EQF", "Equity contributions, dividends and lease payments", List.of(sfp1("GE"), sfp1("RE"), sfp1("OE"), sfp1("LEASE"))),
                        m("OTHER", "Other cash movements", List.of("*")),
                        l("NET", "Net increase (decrease) in cash", "TOTAL", List.of(), List.of("NOP", "INVA", "EQF", "OTHER"), 1, 0),
                        l("CHK", "Cash at the end of the year (computed)", "TOTAL", List.of(), List.of("OPEN", "NET"), 1, 0),
                        l("CLOSE", "Cash and cash equivalents at the end of the period", "CLOSING", sfp("CASH"), List.of(), 1, 0))),
                new TemplateRequest("RCA-SCE", "Statement of Changes in Equity", "SCE", "END=TCE", List.of(
                        l("OPEN", "Balance at the beginning of the year", "OPENING", equity(), List.of(), -1, 0),
                        new TemplateLineRequest("CLOSED", "Profit (loss) closed to equity", "MOVEMENT", equity(), List.of(), -1, 1,
                                List.of(sci1("BSI"), sci1("AS"), sci1("SDG"), sci1("GAIN"), sci1("ONOI"), sci1("PS"), sci1("MOOE"),
                                        sci1("FIN"), sci1("DC"), sci1("NC"), sci1("TAX"), sci1("SUB"))),
                        l("SUR", "Profit (loss) of the year not yet closed", "SURPLUS", List.of(), List.of(), -1, 1),
                        new TemplateLineRequest("OTH", "Other changes in equity", "MOVEMENT", equity(), List.of(), -1, 1, List.of("*")),
                        l("END", "Balance at the end of the period", "TOTAL", List.of(), List.of("OPEN", "CLOSED", "SUR", "OTH"), 1, 0),
                        l("CEQ", "Equity accounts at the end of the period", "CLOSING", equity(), List.of(), -1, 1),
                        l("TCE", "Equity per the statement of financial position", "TOTAL", List.of(), List.of("CEQ", "SUR"), 1, 1))));
    }

    private static List<String> equity() {
        return List.of(sfp1("GE"), sfp1("RE"), sfp1("OE"));
    }

    private static List<String> sfp(String line) {
        return List.of(sfp1(line));
    }

    private static List<String> sci(String line) {
        return List.of(sci1(line));
    }

    private static String sfp1(String line) {
        return "RCA-SFP-" + line;
    }

    private static String sci1(String line) {
        return "RCA-SCI-" + line;
    }

    /** A cash-flow MOVEMENT line over the official cash code. */
    private static TemplateLineRequest m(String code, String label, List<String> counterparts) {
        return new TemplateLineRequest(code, label, "MOVEMENT", sfp("CASH"), List.of(), 1, 1, counterparts);
    }

    private static TemplateLineRequest l(String code, String label, String type, List<String> fs, List<String> totalOf, int sign, int indent) {
        return new TemplateLineRequest(code, label, type, fs, totalOf, sign, indent);
    }
}
