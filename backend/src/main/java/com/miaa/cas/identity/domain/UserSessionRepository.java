package com.miaa.cas.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    @Query("select s from UserSession s where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now")
    List<UserSession> findActive(@Param("userId") UUID userId, @Param("now") Instant now);
}
