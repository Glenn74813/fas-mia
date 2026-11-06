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

    /** Every role, ordered by name, with the users holding it (active users first, then by name). */
    public List<com.miaa.cas.identity.web.IdentityDtos.RoleMembers> roleMembers() {
        java.util.Map<String, com.miaa.cas.identity.web.IdentityDtos.RoleMembers> out = new java.util.LinkedHashMap<>();
        jdbc.query("""
                SELECT r.code, r.name, u.id, u.username, u.display_name, u.status
                FROM roles r LEFT JOIN user_roles ur ON ur.role_id = r.id LEFT JOIN users u ON u.id = ur.user_id
                ORDER BY r.name, u.status, u.display_name""", rs -> {
            String code = rs.getString(1);
            String name = rs.getString(2);
            var role = out.computeIfAbsent(code, c -> new com.miaa.cas.identity.web.IdentityDtos.RoleMembers(c, name, new java.util.ArrayList<>()));
            if (rs.getObject(3) != null) {
                role.members().add(new com.miaa.cas.identity.web.IdentityDtos.RoleMember(rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), rs.getString(6)));
            }
        });
        return List.copyOf(out.values());
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
