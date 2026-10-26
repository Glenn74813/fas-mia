package com.miaa.cas.identity;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read access to the permission catalogue and SoD rules (both maintained through migrations). */
@Repository
public class PermissionCatalog {

    public record PermissionInfo(String code, String description, String category, boolean financial) {
    }

    private final JdbcTemplate jdbc;

    public PermissionCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PermissionInfo> all() {
        return jdbc.query("SELECT code, description, category, financial FROM permissions ORDER BY category, code",
                (rs, i) -> new PermissionInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
    }

    public List<String> unknownCodes(List<String> codes) {
        List<String> known = jdbc.queryForList("SELECT code FROM permissions", String.class);
        return codes.stream().filter(c -> !known.contains(c)).toList();
    }

    public List<SodPolicy.Rule> sodRules() {
        return jdbc.query("SELECT permission_a, permission_b, severity, rationale FROM sod_conflict_rules ORDER BY 1, 2",
                (rs, i) -> new SodPolicy.Rule(rs.getString(1), rs.getString(2),
                        SodPolicy.Severity.valueOf(rs.getString(3)), rs.getString(4)));
    }
}
