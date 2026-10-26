package com.miaa.cas.generalledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Budget control applied inside the posting transaction (implemented by the budgeting module).
 * Postings whose budget was already reserved by their source document skip the availability check.
 */
public interface PostingBudgetGuard {

    record GuardLine(UUID accountId, UUID costCenterId, BigDecimal debit, BigDecimal credit) {
    }

    void onPosting(UUID fiscalYearId, String sourceType, String sourceReference, List<GuardLine> lines,
            boolean budgetReservedBySource);
}
