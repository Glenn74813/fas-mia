package com.miaa.cas.budgeting.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    Optional<Budget> findByFiscalYearId(UUID fiscalYearId);

    List<Budget> findAllByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Budget b where b.id = :id")
    Optional<Budget> findByIdForUpdate(@Param("id") UUID id);
}
