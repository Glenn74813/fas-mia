package com.miaa.cas.accountspayable.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisbursementVoucherRepository extends JpaRepository<DisbursementVoucher, UUID>,
        JpaSpecificationExecutor<DisbursementVoucher> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DisbursementVoucher d where d.id = :id")
    Optional<DisbursementVoucher> findByIdForUpdate(@Param("id") UUID id);
}
