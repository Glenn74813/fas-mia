package com.miaa.cas.accountspayable.domain;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, UUID>,
        JpaSpecificationExecutor<SupplierInvoice> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from SupplierInvoice i where i.id = :id")
    Optional<SupplierInvoice> findByIdForUpdate(@Param("id") UUID id);

    /** Locks in id order so concurrent disbursement vouchers cannot deadlock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from SupplierInvoice i where i.id in :ids order by i.id")
    List<SupplierInvoice> lockAll(@Param("ids") Collection<UUID> ids);
}
