package com.miaa.cas.generalledger.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountingPeriodRepository extends JpaRepository<AccountingPeriod, UUID> {

    List<AccountingPeriod> findByFiscalYearIdOrderByPeriodNumberAsc(UUID fiscalYearId);

    @Query("select p from AccountingPeriod p where p.periodNumber <= 12 and :date between p.startDate and p.endDate")
    Optional<AccountingPeriod> findRegularContaining(@Param("date") LocalDate date);

    Optional<AccountingPeriod> findByFiscalYearIdAndPeriodNumber(UUID fiscalYearId, short periodNumber);

    /** Exclusive lock taken by period status changes; posting takes a shared lock (see PostingService). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AccountingPeriod p where p.id = :id")
    Optional<AccountingPeriod> findByIdForUpdate(@Param("id") UUID id);

    @Query("select p from AccountingPeriod p where p.sequenceKey < :key and p.status <> com.miaa.cas.generalledger.domain.PeriodStatus.CLOSED order by p.sequenceKey")
    List<AccountingPeriod> findUnclosedBefore(@Param("key") int sequenceKey);
}
