package com.miaa.cas.journal;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gap-free official numbers: the counter row stays locked until the posting transaction ends, and a
 * rollback restores it, so committed numbers are consecutive.
 */
@Component
public class NumberingService {

    private final JdbcTemplate jdbc;

    public NumberingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String documentType, int fiscalYear) {
        // Counters for new document types/years are created on first use (prefix "DV-2026-" etc.).
        jdbc.update("INSERT INTO numbering_sequences (document_type, fiscal_year, prefix, next_value) VALUES (?, ?, ?, 1) "
                + "ON CONFLICT DO NOTHING", documentType, fiscalYear, documentType + "-" + fiscalYear + "-");
        List<String> result = jdbc.query("""
                UPDATE numbering_sequences SET next_value = next_value + 1
                WHERE document_type = ? AND fiscal_year = ? RETURNING prefix, next_value - 1 AS issued""",
                (rs, i) -> rs.getString("prefix") + String.format("%06d", rs.getLong("issued")), documentType, fiscalYear);
        if (result.isEmpty()) {
            throw new IllegalStateException("No numbering sequence for " + documentType + " " + fiscalYear);
        }
        return result.get(0);
    }
}
