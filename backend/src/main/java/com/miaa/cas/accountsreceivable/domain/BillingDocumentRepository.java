package com.miaa.cas.accountsreceivable.domain;

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

public interface BillingDocumentRepository extends JpaRepository<BillingDocument, UUID>,
        JpaSpecificationExecutor<BillingDocument> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from BillingDocument d where d.id = :id")
    Optional<BillingDocument> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from BillingDocument d where d.id in :ids order by d.id")
    List<BillingDocument> lockAll(@Param("ids") Collection<UUID> ids);
}
