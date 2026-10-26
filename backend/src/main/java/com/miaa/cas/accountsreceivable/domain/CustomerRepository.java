package com.miaa.cas.accountsreceivable.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    List<Customer> findAllByOrderByNameAsc();

    boolean existsByCode(String code);
}
