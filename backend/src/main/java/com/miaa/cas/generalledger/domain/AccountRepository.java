package com.miaa.cas.generalledger.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByCode(String code);

    List<Account> findAllByOrderByCodeAsc();

    List<Account> findByCodeIn(Collection<String> codes);

    boolean existsByParentId(UUID parentId);

    boolean existsByCode(String code);

    long countByPlaceholderTrueAndStatus(String status);
}
