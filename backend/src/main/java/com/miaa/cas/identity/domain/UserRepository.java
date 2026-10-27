package com.miaa.cas.identity.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    @Query("select u from User u where u.username = lower(:identifier) or lower(u.email) = lower(:identifier)")
    Optional<User> findByIdentifier(@Param("identifier") String identifier);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByUsername(String username);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    /**
     * Optional filters are added only when present: binding a null into {@code lower(:q)} leaves
     * PostgreSQL unable to infer the parameter type.
     */
    default Page<User> search(String q, UserStatus status, Pageable pageable) {
        Specification<User> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(root.get("username"), pattern, '\\'),
                    cb.like(cb.lower(root.get("displayName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("email")), pattern, '\\')));
        }
        // Users who never signed in go last in either direction. Criteria queries cannot say NULLS LAST, so this sort is
        // applied here and the page request is passed on unsorted (Spring Data then keeps this order).
        Sort.Order login = pageable.getSort().getOrderFor("lastLoginAt");
        if (login != null) {
            boolean desc = login.isDescending();
            spec = spec.and((root, query, cb) -> {
                if (!Long.class.equals(query.getResultType()) && !long.class.equals(query.getResultType())) {
                    query.orderBy(cb.asc(cb.<Integer>selectCase().when(cb.isNull(root.get("lastLoginAt")), 1).otherwise(0)),
                            desc ? cb.desc(root.get("lastLoginAt")) : cb.asc(root.get("lastLoginAt")), cb.asc(root.get("id")));
                }
                return cb.conjunction();
            });
            pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        }
        return findAll(spec, pageable);
    }
}
