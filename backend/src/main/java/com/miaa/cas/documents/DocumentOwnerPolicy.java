package com.miaa.cas.documents;

import java.util.UUID;

/** Implemented by each module that owns documents (journal vouchers now; DVs, invoices later). */
public interface DocumentOwnerPolicy {

    String ownerType();

    /** Throws when the current user may not add or remove documents on this owner. */
    void assertCanModify(UUID ownerId);

    /** Throws when the owner does not exist or the current user may not see it. */
    void assertCanView(UUID ownerId);
}
