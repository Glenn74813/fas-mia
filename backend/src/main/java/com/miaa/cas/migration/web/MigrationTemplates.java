package com.miaa.cas.migration.web;

import com.miaa.cas.migration.MigrationService;

/** Empty CAS migration templates. These are CAS layouts, not any legacy system's extract format (C-12). */
final class MigrationTemplates {

    private MigrationTemplates() {
    }

    static String text(MigrationService.Kind kind) {
        String notes = switch (kind) {
            case GL_OPENING -> """
                    # CAS template: GL opening trial balance at the cutover date (first period of a fiscal year).
                    # One row per account, cost centre and subsidiary account. Give either debit or credit (positive, two decimals, no
                    # thousands separators); leave the other empty. Control accounts (AR, AP, ...) need one row per subsidiary account.
                    # The batch becomes a draft OPENING journal voucher that goes through normal approval and posting.
                    """;
            case AR_OPEN_ITEMS -> """
                    # CAS template: customer invoices still open at cutover. Dates yyyy-MM-dd; document_date on or before cutover.
                    # outstanding_amount is what remains unpaid (positive, two decimals). The items must equal each customer's balance
                    # on the AR control account in the posted opening entry; nothing is posted again.
                    """;
            case AP_OPEN_ITEMS -> """
                    # CAS template: supplier invoices still unpaid at cutover. Dates yyyy-MM-dd; invoice_date on or before cutover.
                    # outstanding_amount is what remains payable after withholding (positive, two decimals). The items must equal each
                    # supplier's balance on the AP control account in the posted opening entry; nothing is posted again.
                    """;
            case AR_UNAPPLIED -> """
                    # CAS template: customer collections received before cutover but not yet applied to invoices (credits on account).
                    # unapplied_amount is positive, two decimals. They must equal each customer's balance on the unapplied-collections
                    # account in the posted opening entry. They can be applied to invoices later; they are never offered for deposit.
                    """;
            case AP_ADVANCES -> """
                    # CAS template: advances paid to suppliers or contractors and not yet liquidated at cutover. outstanding_amount is the
                    # balance still to be recovered (positive, two decimals). The items must equal each supplier's balance on the account
                    # mapped to SUPPLIER_ADVANCES in the posted opening entry. Liquidating advances in CAS awaits AVTECH policy (C-31).
                    """;
            case FA_REGISTER -> """
                    # CAS template: the fixed-asset register at cutover. asset_number is the property number (unique). Dates yyyy-MM-dd;
                    # in_service_date before cutover. cost, residual_value and accumulated_depreciation in pesos (two decimals);
                    # useful_life_months and remaining_life_months as whole months (remaining 0 only when fully depreciated).
                    # Depreciation continues from the cutover month over the remaining life. Totals per category account must equal
                    # the asset cost and accumulated depreciation balances in the posted opening entry.
                    """;
            case INVENTORY_STOCK -> """
                    # CAS template: stock on hand at cutover, one row per item and warehouse. quantity up to four decimals, unit_cost up to
                    # six. Value = quantity x unit cost, rounded to centavos; totals per inventory account must equal the posted opening
                    # entry. Only items with no stock history in CAS can receive opening stock.
                    """;
        };
        return notes + String.join(",", kind.columns()) + "\n";
    }
}
