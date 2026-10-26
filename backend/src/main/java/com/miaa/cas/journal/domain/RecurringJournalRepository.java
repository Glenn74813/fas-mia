package com.miaa.cas.journal.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringJournalRepository extends JpaRepository<RecurringJournal, UUID> {

    List<RecurringJournal> findAllByOrderByNameAsc();
}
