package com.miaa.cas.generalledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountPurposeTest {

    @Test
    void supplierInvoicesDebitExpensesAndBoughtAssetsButNotCashOrReceivables() {
        AccountPurpose p = AccountPurpose.SUPPLIER_INVOICE_LINE;
        assertThat(p.fits("EXPENSE", "DEBIT", "RCA-SCI-MOOE", null, true)).isTrue();
        assertThat(p.fits("ASSET", "DEBIT", "RCA-SFP-INV", null, false)).isTrue();
        assertThat(p.fits("ASSET", "DEBIT", "RCA-SFP-PPE", null, false)).isTrue();
        assertThat(p.fits("ASSET", "DEBIT", "RCA-SFP-CASH", null, false)).isFalse();
        assertThat(p.fits("ASSET", "DEBIT", "RCA-SFP-REC", "CUSTOMER", false)).isFalse();
        assertThat(p.fits("ASSET", "CREDIT", "RCA-SFP-PPE", null, false)).isFalse();
        assertThat(p.fits("LIABILITY", "CREDIT", "RCA-SFP-OP", null, false)).isTrue();
        assertThat(p.fits("LIABILITY", "CREDIT", "RCA-SFP-FL", "SUPPLIER", false)).isFalse();
        assertThat(p.fits("REVENUE", "CREDIT", "RCA-SCI-BSI", null, false)).isFalse();
    }

    @Test
    void statementLinesMatchOnBothCharts() {
        assertThat(AccountPurpose.CASH.fits("ASSET", "DEBIT", "PLC-SFP-CASH", null, false)).isTrue();
        assertThat(AccountPurpose.CASH.fits("ASSET", "DEBIT", "RCA-SFP-CASH", null, false)).isTrue();
        assertThat(AccountPurpose.CASH.fits("ASSET", "DEBIT", "RCA-SFP-INV", null, false)).isFalse();
        assertThat(AccountPurpose.ASSET_ACCUMULATED.fits("ASSET", "CREDIT", "RCA-SFP-PPE", null, false)).isTrue();
        assertThat(AccountPurpose.ASSET_COST.fits("ASSET", "CREDIT", "RCA-SFP-PPE", null, false)).isFalse();
    }

    @Test
    void controlMappingsNeedTheirSubLedger() {
        assertThat(AccountPurpose.MAPPINGS.get("AP_TRADE_PAYABLE").fits("LIABILITY", "CREDIT", "RCA-SFP-FL", "SUPPLIER", false)).isTrue();
        assertThat(AccountPurpose.MAPPINGS.get("AP_TRADE_PAYABLE").fits("LIABILITY", "CREDIT", "RCA-SFP-FL", null, false)).isFalse();
        assertThat(AccountPurpose.MAPPINGS.get("AR_TRADE_RECEIVABLE").fits("ASSET", "DEBIT", "RCA-SFP-REC", "CUSTOMER", false)).isTrue();
        assertThat(AccountPurpose.MAPPINGS.get("UNAPPLIED_COLLECTIONS").fits("LIABILITY", "CREDIT", "RCA-SFP-OP", "CUSTOMER", false)).isTrue();
        assertThat(AccountPurpose.forTaxType("VAT_INPUT")).isEqualTo(AccountPurpose.TAX_ASSET);
        assertThat(AccountPurpose.forTaxType("EWT")).isEqualTo(AccountPurpose.TAX_LIABILITY);
    }
}
