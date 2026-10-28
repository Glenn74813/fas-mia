package com.miaa.cas.generalledger;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.generalledger.domain.Account;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What a postable account may be chosen for outside a journal voucher. One list serves both sides: the chart sent to
 * the browser marks every postable account with the purposes it fits, so each dropdown offers only those, and each
 * service refuses any other account for that field ({@link #require}).
 *
 * <p>Statement lines are matched by their last segment ({@code -CASH}, {@code -INV}, {@code -PPE} ...), which the
 * development placeholder chart and COA's chart for government corporations share.
 */
public enum AccountPurpose {

    JOURNAL_LINE("any postable account"),
    SUPPLIER_INVOICE_LINE("an expense account, an asset bought from a supplier (inventory, prepayment, property and equipment), "
            + "or a liability the invoice settles without a sub-ledger (goods received not invoiced, accrued expenses)"),
    REVENUE_LINE("a revenue account"),
    BUDGET_LINE("a budget-controlled account"),
    ALLOCATION("an expense account"),
    CASH("a cash account (statement line ending -CASH) without a sub-ledger"),
    INVENTORY_STOCK("an inventory account (statement line ending -INV) without a sub-ledger"),
    EXPENSE("an expense account"),
    ASSET_COST("a property, equipment, investment property, intangible or right-of-use asset with a debit balance"),
    ASSET_ACCUMULATED("an accumulated depreciation or impairment account (credit balance) on the same asset lines"),
    ASSET_UNDER_CONSTRUCTION("an asset account kept by project (OTHER sub-ledger)"),
    ASSET_OFFSET("an account without a sub-ledger"),
    OPEN_ITEM("an asset or liability account"),
    EQUITY("an equity account"),
    TAX_ASSET("an asset account without a sub-ledger"),
    TAX_LIABILITY("a liability account without a sub-ledger"),
    SUPPLIER_CONTROL("a liability account with a supplier sub-ledger"),
    SUPPLIER_ADVANCE("an asset account with a supplier sub-ledger"),
    CUSTOMER_CONTROL("an asset account with a customer sub-ledger"),
    CUSTOMER_DEPOSIT("a liability account with a customer sub-ledger"),
    LIABILITY("a liability account without a sub-ledger"),
    GAIN("a revenue account"),
    LOSS("an expense account");

    /** Statement lines a supplier invoice may not debit: cash, receivables and financial assets move by other documents. */
    private static final Set<String> NOT_FROM_SUPPLIERS = Set.of("CASH", "REC", "FIN");
    private static final Set<String> ASSET_LINES = Set.of("PPE", "IP", "INT", "ROU");

    /** The purpose each control-account mapping needs. */
    public static final Map<String, AccountPurpose> MAPPINGS = Map.ofEntries(
            Map.entry("AP_TRADE_PAYABLE", SUPPLIER_CONTROL), Map.entry("INPUT_VAT", TAX_ASSET),
            Map.entry("AR_TRADE_RECEIVABLE", CUSTOMER_CONTROL), Map.entry("OUTPUT_VAT", TAX_LIABILITY),
            Map.entry("UNAPPLIED_COLLECTIONS", CUSTOMER_DEPOSIT), Map.entry("CASH_ON_HAND", CASH), Map.entry("CREDITABLE_WHT", TAX_ASSET),
            Map.entry("FA_DISPOSAL_GAIN", GAIN), Map.entry("FA_DISPOSAL_LOSS", LOSS), Map.entry("INVENTORY_GRIR", LIABILITY),
            Map.entry("INVENTORY_VARIANCE", EXPENSE), Map.entry("INVENTORY_REVALUATION", EXPENSE),
            Map.entry("SUPPLIER_ADVANCES", SUPPLIER_ADVANCE));

    private final String requirement;

    AccountPurpose(String requirement) {
        this.requirement = requirement;
    }

    public String requirement() {
        return requirement;
    }

    /** The purpose a tax code of this type needs for its account. */
    public static AccountPurpose forTaxType(String taxType) {
        return "VAT_INPUT".equals(taxType) ? TAX_ASSET : TAX_LIABILITY;
    }

    public boolean fits(Account a) {
        return a.isPostable() && fits(a.getAccountType().name(), a.getNormalBalance().name(), a.getFsLineCode(), a.getSubsidiaryType(),
                a.isBudgetControlled());
    }

    boolean fits(String type, String normal, String fsLineCode, String slType, boolean budgetControlled) {
        String line = fsLineCode == null ? "" : fsLineCode.substring(fsLineCode.lastIndexOf('-') + 1);
        boolean noSl = slType == null;
        boolean asset = "ASSET".equals(type);
        return switch (this) {
            case JOURNAL_LINE -> true;
            case SUPPLIER_INVOICE_LINE -> "EXPENSE".equals(type) || (asset && "DEBIT".equals(normal) && noSl && !NOT_FROM_SUPPLIERS.contains(line))
                    || ("LIABILITY".equals(type) && noSl);
            case REVENUE_LINE, GAIN -> "REVENUE".equals(type);
            case BUDGET_LINE -> budgetControlled;
            case ALLOCATION, EXPENSE, LOSS -> "EXPENSE".equals(type);
            case CASH -> asset && noSl && "CASH".equals(line);
            case INVENTORY_STOCK -> asset && noSl && "DEBIT".equals(normal) && "INV".equals(line);
            case ASSET_COST -> asset && noSl && "DEBIT".equals(normal) && ASSET_LINES.contains(line);
            case ASSET_ACCUMULATED -> asset && noSl && "CREDIT".equals(normal) && ASSET_LINES.contains(line);
            case ASSET_UNDER_CONSTRUCTION -> asset && "OTHER".equals(slType);
            case ASSET_OFFSET -> noSl;
            case OPEN_ITEM -> asset || "LIABILITY".equals(type);
            case EQUITY -> "EQUITY".equals(type);
            case TAX_ASSET -> asset && noSl;
            case TAX_LIABILITY, LIABILITY -> "LIABILITY".equals(type) && noSl;
            case SUPPLIER_CONTROL -> "LIABILITY".equals(type) && "SUPPLIER".equals(slType);
            case SUPPLIER_ADVANCE -> asset && "SUPPLIER".equals(slType);
            case CUSTOMER_CONTROL -> asset && "CUSTOMER".equals(slType);
            case CUSTOMER_DEPOSIT -> "LIABILITY".equals(type) && "CUSTOMER".equals(slType);
        };
    }

    /**
     * Refuses an account that is not active, postable and fit for the purpose, naming the field and what it needs.
     * Every service that accepts an account outside a journal voucher calls this.
     */
    public static void require(JdbcTemplate jdbc, UUID accountId, AccountPurpose purpose, String field) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT code, account_type, normal_balance, fs_line_code, subsidiary_type, budget_controlled, postable, status
                FROM chart_of_accounts WHERE id = ?""", accountId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Account");
        }
        Map<String, Object> a = rows.get(0);
        boolean ok = Boolean.TRUE.equals(a.get("postable")) && "ACTIVE".equals(a.get("status"))
                && purpose.fits((String) a.get("account_type"), (String) a.get("normal_balance"), (String) a.get("fs_line_code"),
                        (String) a.get("subsidiary_type"), Boolean.TRUE.equals(a.get("budget_controlled")));
        if (!ok) {
            throw AccountService.rule(field + ": account " + a.get("code") + " cannot be used here; it must be active and "
                    + purpose.requirement() + ".");
        }
    }

    /** Purposes a postable account fits, for the chart sent to the browser; none for headings. */
    public static List<String> of(Account a) {
        return a.isPostable() ? Arrays.stream(values()).filter(p -> p.fits(a)).map(Enum::name).toList() : List.of();
    }
}
