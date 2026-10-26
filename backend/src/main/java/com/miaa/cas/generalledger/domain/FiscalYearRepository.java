package com.miaa.cas.generalledger.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalYearRepository extends JpaRepository<FiscalYear, UUID> {

    List<FiscalYear> findAllByOrderByFiscalYearDesc();

    boolean existsByFiscalYear(int fiscalYear);

    @Query("select f from FiscalYear f where :date between f.startDate and f.endDate")
    Optional<FiscalYear> findContaining(@Param("date") LocalDate date);
}
