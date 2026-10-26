package com.miaa.cas.generalledger.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodStatusChangeRepository extends JpaRepository<PeriodStatusChange, UUID> {

    List<PeriodStatusChange> findByPeriodIdOrderByRequestedAtDesc(UUID periodId);

    List<PeriodStatusChange> findByRequestStatusOrderByRequestedAtAsc(String requestStatus);
}
