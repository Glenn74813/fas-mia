package com.miaa.cas.config.dev;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.financialreporting.ReportDtos.TemplateLineRequest;
import com.miaa.cas.financialreporting.ReportDtos.TemplateRequest;
import com.miaa.cas.financialreporting.StatementTemplateService;
import com.miaa.cas.tickets.SlaPolicyService;
import com.miaa.cas.fixedassets.AssetSetupService;
import com.miaa.cas.fixedassets.FaDtos.CategoryRequest;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.generalledger.AccountService;
import com.miaa.cas.generalledger.domain.AccountRepository;
import com.miaa.cas.tax.TaxCodeService;
import com.miaa.cas.tax.TaxCodeService.TaxCodeRequest;
import com.miaa.cas.treasury.BankAccountService;
import com.miaa.cas.treasury.BankAccountService.BankAccountRequest;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT ONLY. Placeholder tax codes, the AP control-account mapping and a placeholder bank
 * account so payables can be exercised locally. Tax codes here are illustrative placeholders, not
 * validated BIR alphanumeric tax codes or rates; MIAA's tax team configures the real ones.
 */
@Component
@Profile("dev")
public class DevPayablesSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevPayablesSeeder.class);

    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final TaxCodeService taxCodes;
    private final AccountMappingService mappings;
    private final BankAccountService banks;
    private final AccountService accountService;
    private final AssetSetupService assetSetup;
    private final StatementTemplateService statementTemplates;
    private final SlaPolicyService slaPolicies;

    public DevPayablesSeeder(JdbcTemplate jdbc, AccountRepository accounts, TaxCodeService taxCodes,
            AccountMappingService mappings, BankAccountService banks, AccountService accountService, AssetSetupService assetSetup,
            StatementTemplateService statementTemplates, SlaPolicyService slaPolicies) {
        this.slaPolicies = slaPolicies;
        this.assetSetup = assetSetup;
        this.statementTemplates = statementTemplates;
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.taxCodes = taxCodes;
        this.mappings = mappings;
        this.banks = banks;
        this.accountService = accountService;
    }

    void seed() {
        addMissingPlaceholderAccounts();
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM tax_codes", Integer.class);
        if (existing != null && existing == 0) {
            taxCodes.create(new TaxCodeRequest("PLC-EWT-GOODS", "[PLACEHOLDER] Expanded withholding - goods (verify ATC and rate)",
                    "EWT", new BigDecimal("0.0100"), id("PLC-2201")), true, Actor.SYSTEM);
            taxCodes.create(new TaxCodeRequest("PLC-EWT-SERV", "[PLACEHOLDER] Expanded withholding - services (verify ATC and rate)",
                    "EWT", new BigDecimal("0.0200"), id("PLC-2201")), true, Actor.SYSTEM);
            taxCodes.create(new TaxCodeRequest("PLC-VAT-IN", "[PLACEHOLDER] Input VAT (verify rate)", "VAT_INPUT",
                    new BigDecimal("0.1200"), id("PLC-1204")), true, Actor.SYSTEM);
            taxCodes.create(new TaxCodeRequest("PLC-VAT-OUT", "[PLACEHOLDER] Output VAT (verify rate)", "VAT_OUTPUT",
                    new BigDecimal("0.1200"), id("PLC-2202")), true, Actor.SYSTEM);
            mappings.set("AP_TRADE_PAYABLE", id("PLC-2101"), Actor.SYSTEM);
            banks.create(new BankAccountRequest("PLC-BANK-01", "[PLACEHOLDER] Depository bank", "MIAA disbursement account",
                    "0000", id("PLC-1102")), Actor.SYSTEM);
            log.warn("DEV: seeded PLACEHOLDER tax codes, control-account mappings and a placeholder bank account");
        }
        // Receivables mappings (Phase 3b); also corrects dev databases that mapped unapplied collections to PLC-2302.
        mapIfDifferent("UNAPPLIED_COLLECTIONS", "PLC-2303");
        mapIfDifferent("CREDITABLE_WHT", "PLC-1205");
        mapIfDifferent("AR_TRADE_RECEIVABLE", "PLC-1201");
        mapIfDifferent("CASH_ON_HAND", "PLC-1101");
        // Fixed assets (Phase 3d): construction in progress keeps a per-project subsidiary ledger.
        jdbc.update("UPDATE chart_of_accounts c SET subsidiary_type = 'OTHER' WHERE c.code = 'PLC-1410' AND c.subsidiary_type IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM journal_entry_lines l WHERE l.account_id = c.id)");
        mapIfDifferent("FA_DISPOSAL_GAIN", "PLC-4204");
        mapIfDifferent("FA_DISPOSAL_LOSS", "PLC-5302");
        Integer categories = jdbc.queryForObject("SELECT count(*) FROM asset_categories", Integer.class);
        if (categories != null && categories == 0) {
            assetSetup.createCategory(new CategoryRequest("PLC-FA-BLDG", "[PLACEHOLDER] Buildings (verify useful life, C-30)",
                    id("PLC-1401"), id("PLC-1409"), id("PLC-5301"), 360, null), true, Actor.SYSTEM);
            assetSetup.createCategory(new CategoryRequest("PLC-FA-EQPT", "[PLACEHOLDER] Machinery and equipment (verify useful life, C-30)",
                    id("PLC-1402"), id("PLC-1409"), id("PLC-5301"), 60, null), true, Actor.SYSTEM);
            log.warn("DEV: seeded PLACEHOLDER asset categories");
        }
        // Inventory (Phase 3e).
        mapIfDifferent("INVENTORY_GRIR", "PLC-2104");
        mapIfDifferent("INVENTORY_VARIANCE", "PLC-5303");
        // Placeholder only: the placeholder chart has no revaluation account (MIAA's chart decides, C-07).
        mapIfDifferent("INVENTORY_REVALUATION", "PLC-5303");
        // Supplier advances carried over at cutover (Phase 5b); the placeholder chart has a supplier sub-ledger account for them.
        if (jdbc.queryForObject("SELECT count(*) FROM chart_of_accounts WHERE code = 'PLC-1206'", Integer.class) == 1) {
            mapIfDifferent("SUPPLIER_ADVANCES", "PLC-1206");
        }
        Integer warehouses = jdbc.queryForObject("SELECT count(*) FROM warehouses", Integer.class);
        if (warehouses != null && warehouses == 0) {
            jdbc.update("INSERT INTO warehouses (id, code, name, status, created_at, created_by) VALUES (?, 'PLC-WH-MAIN', "
                    + "'[PLACEHOLDER] Main supply warehouse', 'ACTIVE', now(), 'system')", java.util.UUID.randomUUID());
            log.warn("DEV: seeded a PLACEHOLDER warehouse");
        }
        // Financial reporting (Phase 4a): placeholder statement templates over the placeholder line codes.
        // Created per code, so databases seeded before gap 14 also get the movement statements.
        for (TemplateRequest t : placeholderTemplates()) {
            if (Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM report_templates WHERE code = ?)", Boolean.class, t.code()))) {
                statementTemplates.activate(statementTemplates.create(t, true, Actor.SYSTEM).id(), Actor.SYSTEM);
                log.warn("DEV: seeded PLACEHOLDER statement template {}", t.code());
            }
        }
        // Ticketing (Phase 4d): illustrative SLA values only; the contract's commitments replace them (C-15).
        Integer policies = jdbc.queryForObject("SELECT count(*) FROM sla_policies", Integer.class);
        if (policies != null && policies == 0) {
            int[][] times = {{30, 240, 0}, {60, 480, 1}, {240, 1440, 1}, {480, 2880, 1}};
            for (String type : List.of("INCIDENT", "SERVICE_REQUEST", "CHANGE_REQUEST", "ENHANCEMENT")) {
                for (int p = 0; p < 4; p++) {
                    slaPolicies.create(new SlaPolicyService.PolicyRequest(slaPolicies.contractPhase(), type, "P" + (p + 1), times[p][0], times[p][1],
                            times[p][2] == 1), true, Actor.SYSTEM);
                }
            }
            log.warn("DEV: seeded PLACEHOLDER SLA policies");
        }
    }

    /** Illustrative layouts only; MIAA's approved statement formats replace them (C-07). */
    public static List<TemplateRequest> placeholderTemplates() {
        return List.of(
                new TemplateRequest("PLC-SFP", "[PLACEHOLDER] Statement of Financial Position", "SFP", "TA=TLE", List.of(
                        l("H-A", "Assets", "HEADER", List.of(), List.of(), 1, 0),
                        l("CASH", "Cash and cash equivalents", "LINE", List.of("PLC-SFP-CASH"), List.of(), 1, 1),
                        l("REC", "Receivables", "LINE", List.of("PLC-SFP-REC"), List.of(), 1, 1),
                        l("INV", "Inventories", "LINE", List.of("PLC-SFP-INV"), List.of(), 1, 1),
                        l("PPE", "Property, plant and equipment (net)", "LINE", List.of("PLC-SFP-PPE"), List.of(), 1, 1),
                        l("TA", "Total assets", "TOTAL", List.of(), List.of("CASH", "REC", "INV", "PPE"), 1, 0),
                        l("H-L", "Liabilities", "HEADER", List.of(), List.of(), 1, 0),
                        l("PAY", "Payables", "LINE", List.of("PLC-SFP-PAY"), List.of(), -1, 1),
                        l("TAX", "Taxes payable", "LINE", List.of("PLC-SFP-TAX"), List.of(), -1, 1),
                        l("DEF", "Deposits and deferred credits", "LINE", List.of("PLC-SFP-DEF"), List.of(), -1, 1),
                        l("TL", "Total liabilities", "TOTAL", List.of(), List.of("PAY", "TAX", "DEF"), 1, 0),
                        l("H-E", "Equity", "HEADER", List.of(), List.of(), 1, 0),
                        l("EQ", "Government equity and accumulated surplus", "LINE", List.of("PLC-SFP-EQ"), List.of(), -1, 1),
                        l("SUR", "Surplus (deficit) not yet closed", "SURPLUS", List.of(), List.of(), -1, 1),
                        l("TE", "Total equity", "TOTAL", List.of(), List.of("EQ", "SUR"), 1, 0),
                        l("TLE", "Total liabilities and equity", "TOTAL", List.of(), List.of("TL", "TE"), 1, 0))),
                new TemplateRequest("PLC-SFPERF", "[PLACEHOLDER] Statement of Financial Performance", "SFPERF", null, List.of(
                        l("REV", "Revenue", "LINE", List.of("PLC-SFPERF-REV"), List.of(), -1, 0),
                        l("EXP", "Expenses", "LINE", List.of("PLC-SFPERF-EXP"), List.of(), 1, 0),
                        l("NET", "Surplus (deficit) for the period", "TOTAL", List.of(), List.of("REV", "-EXP"), 1, 0))),
                // Direct method: the change in cash classified by what each entry's other lines are.
                new TemplateRequest("PLC-SCF", "[PLACEHOLDER] Cash Flow Statement", "SCF", "CHK=CLOSE", List.of(
                        l("OPEN", "Cash and cash equivalents at the beginning of the year", "OPENING", List.of("PLC-SFP-CASH"), List.of(), 1, 0),
                        l("H-OP", "Cash flows from operating activities", "HEADER", List.of(), List.of(), 1, 0),
                        m("COLL", "Collections from customers and other revenue", List.of("PLC-SFP-REC", "PLC-SFPERF-REV", "PLC-SFP-DEF"), 1),
                        m("PAYS", "Payments to suppliers and for expenses", List.of("PLC-SFP-PAY", "PLC-SFPERF-EXP", "PLC-SFP-INV"), 1),
                        m("TAXES", "Taxes remitted", List.of("PLC-SFP-TAX"), 1),
                        l("NOP", "Net cash from operating activities", "TOTAL", List.of(), List.of("COLL", "PAYS", "TAXES"), 1, 0),
                        l("H-INV", "Cash flows from investing activities", "HEADER", List.of(), List.of(), 1, 0),
                        m("PPE", "Purchase and sale of property, plant and equipment", List.of("PLC-SFP-PPE"), 1),
                        l("H-FIN", "Cash flows from financing activities", "HEADER", List.of(), List.of(), 1, 0),
                        m("EQF", "Equity contributions and distributions", List.of("PLC-SFP-EQ"), 1),
                        m("OTHER", "Other cash movements", List.of("*"), 1),
                        l("NET", "Net increase (decrease) in cash", "TOTAL", List.of(), List.of("NOP", "PPE", "EQF", "OTHER"), 1, 0),
                        l("CHK", "Cash at the end of the year (computed)", "TOTAL", List.of(), List.of("OPEN", "NET"), 1, 0),
                        l("CLOSE", "Cash and cash equivalents at the end of the period", "CLOSING", List.of("PLC-SFP-CASH"), List.of(), 1, 0))),
                new TemplateRequest("PLC-SCE", "[PLACEHOLDER] Statement of Changes in Equity", "SCE", "END=TCE", List.of(
                        l("OPEN", "Balance at the beginning of the year", "OPENING", List.of("PLC-SFP-EQ"), List.of(), -1, 0),
                        new TemplateLineRequest("CLOSED", "Surplus (deficit) closed to equity", "MOVEMENT", List.of("PLC-SFP-EQ"), List.of(), -1, 1,
                                List.of("PLC-SFPERF-REV", "PLC-SFPERF-EXP")),
                        l("SUR", "Surplus (deficit) of the year not yet closed", "SURPLUS", List.of(), List.of(), -1, 1),
                        new TemplateLineRequest("OTH", "Other changes in equity", "MOVEMENT", List.of("PLC-SFP-EQ"), List.of(), -1, 1, List.of("*")),
                        l("END", "Balance at the end of the period", "TOTAL", List.of(), List.of("OPEN", "CLOSED", "SUR", "OTH"), 1, 0),
                        l("CEQ", "Equity accounts at the end of the period", "CLOSING", List.of("PLC-SFP-EQ"), List.of(), -1, 1),
                        l("TCE", "Equity per the statement of financial position", "TOTAL", List.of(), List.of("CEQ", "SUR"), 1, 1))));
    }

    /** A cash-flow MOVEMENT line over the placeholder cash code. */
    private static TemplateLineRequest m(String code, String label, List<String> counterparts, int sign) {
        return new TemplateLineRequest(code, label, "MOVEMENT", List.of("PLC-SFP-CASH"), List.of(), sign, 1, counterparts);
    }

    private static TemplateLineRequest l(String code, String label, String type, List<String> fs, List<String> totalOf, int sign, int indent) {
        return new TemplateLineRequest(code, label, type, fs, totalOf, sign, indent);
    }

    /** Dev databases created before a phase added placeholder accounts get the missing rows in place. */
    private void addMissingPlaceholderAccounts() {
        try (var in = new org.springframework.core.io.ClassPathResource("seed/placeholder-chart-of-accounts.csv").getInputStream()) {
            java.util.List<String> lines = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
            StringBuilder csv = new StringBuilder(lines.get(0)).append('\n');
            int missing = 0;
            for (String line : lines.subList(1, lines.size())) {
                if (accounts.findByCode(line.substring(0, line.indexOf(','))).isEmpty()) {
                    csv.append(line).append('\n');
                    missing++;
                }
            }
            if (missing > 0) {
                accountService.importCsv(new java.io.ByteArrayInputStream(csv.toString().getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)), true, Actor.SYSTEM);
                log.warn("DEV: added {} missing PLACEHOLDER accounts", missing);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void mapIfDifferent(String key, String code) {
        java.util.UUID target = id(code);
        boolean same = mappings.list().stream().anyMatch(m -> m.key().equals(key) && target.equals(m.accountId()));
        if (!same) {
            mappings.set(key, target, Actor.SYSTEM);
        }
    }

    private java.util.UUID id(String code) {
        return accounts.findByCode(code).orElseThrow().getId();
    }
}
