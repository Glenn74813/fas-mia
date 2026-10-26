package com.miaa.cas.generalledger.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CostCenterRepository extends JpaRepository<CostCenter, UUID> {

    List<CostCenter> findAllByOrderByCodeAsc();

    boolean existsByCode(String code);
}
