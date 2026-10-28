package com.miaa.cas.identity;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code user_roles} and to the effective permission set derived from it. */
@Repository
public class RoleAssignments {

    private final JdbcTemplate jdbc;

    public RoleAssignments(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> roleCodes(UUID userId) {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT r.code FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?",
                String.class, userId));
    }

    public Set<String> effectivePermissions(UUID userId) {
        return new TreeSet<>(jdbc.queryForList("""
                SELECT DISTINCT rp.permission_code FROM user_roles ur
                JOIN role_permissions rp ON rp.role_id = ur.role_id WHERE ur.user_id = ?""", String.class, userId));
    }

    /** Effective permissions a user would hold if role {@code roleId} had {@code replacement} permissions. */
    public Set<String> effectivePermissionsWithRoleReplaced(UUID userId, UUID roleId, Collection<String> replacement) {
        Set<String> result = new TreeSet<>(jdbc.queryForList("""
                SELECT DISTINCT rp.permission_code FROM user_roles ur
                JOIN role_permissions rp ON rp.role_id = ur.role_id
                WHERE ur.user_id = ? AND ur.role_id <> ?""", String.class, userId, roleId));
        result.addAll(replacement);
        return result;
    }

    public List<UUID> usersWithRole(UUID roleId) {
        return jdbc.queryForList("SELECT user_id FROM user_roles WHERE role_id = ?", UUID.class, roleId);
    }

    /** Active holders of the role (what the roles page shows); deactivated users keep the grant for the record. */
    public long countUsersWithRole(UUID roleId) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id WHERE ur.role_id = ? AND u.status = 'ACTIVE'""",
                Long.class, roleId);
        return n == null ? 0 : n;
    }

    public boolean anyActiveUserHasRole(String roleCode) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                JOIN users u ON u.id = ur.user_id WHERE r.code = ? AND u.status = 'ACTIVE')""", Boolean.class, roleCode);
        return Boolean.TRUE.equals(exists);
    }

    public void replaceRoles(UUID userId, Collection<UUID> roleIds, String grantedBy) {
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
        for (UUID roleId : roleIds) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id, granted_by) VALUES (?, ?, ?)", userId, roleId, grantedBy);
        }
    }
}
