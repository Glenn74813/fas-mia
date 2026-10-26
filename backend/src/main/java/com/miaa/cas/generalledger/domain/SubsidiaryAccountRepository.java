package com.miaa.cas.generalledger.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubsidiaryAccountRepository extends JpaRepository<SubsidiaryAccount, UUID> {

    List<SubsidiaryAccount> findAllByOrderBySlTypeAscCodeAsc();

    boolean existsBySlTypeAndCode(String slType, String code);
}
