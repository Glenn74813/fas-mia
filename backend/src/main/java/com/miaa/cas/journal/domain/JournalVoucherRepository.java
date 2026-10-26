package com.miaa.cas.journal.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalVoucherRepository extends JpaRepository<JournalVoucher, UUID>, JpaSpecificationExecutor<JournalVoucher> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from JournalVoucher v where v.id = :id")
    Optional<JournalVoucher> findByIdForUpdate(@Param("id") UUID id);

    @Query("select v from JournalVoucher v where v.status in :statuses order by v.updatedAt asc")
    List<JournalVoucher> findByStatusIn(@Param("statuses") List<VoucherStatus> statuses);

    @Query("select count(v) from JournalVoucher v where v.periodId = :periodId and v.status in :statuses")
    long countInPeriod(@Param("periodId") UUID periodId, @Param("statuses") List<VoucherStatus> statuses);
}
