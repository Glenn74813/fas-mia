package com.miaa.cas.journal.domain;

public enum VoucherStatus {
    DRAFT, SUBMITTED, APPROVED, POSTED, RETURNED, REJECTED, CANCELLED;

    public boolean isEditable() {
        return this == DRAFT || this == RETURNED;
    }

    /** Statuses that still need action before the period can close cleanly. */
    public boolean isUnposted() {
        return this == DRAFT || this == SUBMITTED || this == APPROVED || this == RETURNED;
    }
}
