package com.miaa.cas.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MfaMethodRepository extends JpaRepository<MfaMethod, UUID> {

    Optional<MfaMethod> findByUserIdAndMethodTypeAndStatus(UUID userId, String methodType, String status);

    List<MfaMethod> findByUserIdAndStatus(UUID userId, String status);
}
