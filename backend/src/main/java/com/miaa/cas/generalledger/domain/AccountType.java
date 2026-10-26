package com.miaa.cas.generalledger.domain;

public enum AccountType {
    ASSET(NormalBalance.DEBIT, true),
    LIABILITY(NormalBalance.CREDIT, true),
    EQUITY(NormalBalance.CREDIT, true),
    REVENUE(NormalBalance.CREDIT, false),
    EXPENSE(NormalBalance.DEBIT, false);

    private final NormalBalance defaultNormalBalance;
    private final boolean balanceSheet;

    AccountType(NormalBalance defaultNormalBalance, boolean balanceSheet) {
        this.defaultNormalBalance = defaultNormalBalance;
        this.balanceSheet = balanceSheet;
    }

    public NormalBalance defaultNormalBalance() {
        return defaultNormalBalance;
    }

    /** Balance-sheet (real) accounts carry balances across years; revenue/expense (nominal) reset yearly. */
    public boolean isBalanceSheet() {
        return balanceSheet;
    }
}
