package com.miaa.cas.journal.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Value of one debit or credit line, independent of where it is stored. */
public record LineData(UUID accountId, UUID costCenterId, UUID subsidiaryAccountId, String description, BigDecimal debit,
        BigDecimal credit) {

    public LineData mirrored() {
        return new LineData(accountId, costCenterId, subsidiaryAccountId, description, credit, debit);
    }
}
