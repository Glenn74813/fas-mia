package com.miaa.cas.organization;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.security.CurrentActor;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organisation administration (MS §6.2, FR-ORG-01): organisations, their offices and departments, and each internal
 * user's office assignment. Codes are fixed once created because assignments and reports refer to them; names and
 * status can change. A unit cannot be deactivated while active units or current assignments sit under it. Each user
 * has at most one current assignment: a new one closes the previous on the day before it starts, keeping the history.
 */
@Service
public class OrganizationService {

    public record Department(UUID id, UUID officeId, String code, String name, String status, UUID costCenterId, String costCenterCode,
            long currentUsers, long version) {
    }

    public record Office(UUID id, UUID organizationId, String code, String name, String status, List<Department> departments,
            long currentUsers, long version) {
    }

    public record Organization(UUID id, String code, String name, String tin, String status, List<Office> offices, long version) {
    }

    public record Assignment(UUID id, UUID userId, String username, UUID officeId, String officeCode, String officeName, UUID departmentId,
            String departmentCode, String departmentName, LocalDate effectiveFrom, LocalDate effectiveTo, String createdBy, String endedBy) {
    }

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,19}");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public OrganizationService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- structure

    @Transactional(readOnly = true)
    public List<Organization> tree() {
        List<Map<String, Object>> depts = jdbc.queryForList("""
                SELECT d.*, cc.code AS cc_code, (SELECT count(*) FROM user_office_assignments a WHERE a.department_id = d.id AND a.effective_to IS NULL)
                    AS current_users FROM departments d LEFT JOIN cost_centers cc ON cc.id = d.cost_center_id ORDER BY d.code""");
        List<Map<String, Object>> offices = jdbc.queryForList("""
                SELECT o.*, (SELECT count(*) FROM user_office_assignments a WHERE a.office_id = o.id AND a.effective_to IS NULL) AS current_users
                FROM offices o ORDER BY o.code""");
        return jdbc.queryForList("SELECT * FROM organizations ORDER BY code").stream().map(org -> new Organization((UUID) org.get("id"),
                (String) org.get("code"), (String) org.get("name"), (String) org.get("tin"), (String) org.get("status"),
                offices.stream().filter(o -> o.get("organization_id").equals(org.get("id"))).map(o -> new Office((UUID) o.get("id"),
                        (UUID) o.get("organization_id"), (String) o.get("code"), (String) o.get("name"), (String) o.get("status"),
                        depts.stream().filter(d -> d.get("office_id").equals(o.get("id"))).map(OrganizationService::department).toList(),
                        ((Number) o.get("current_users")).longValue(), ((Number) o.get("version")).longValue())).toList(),
                ((Number) org.get("version")).longValue())).toList();
    }

    @Transactional
    public void updateOrganization(UUID id, long version, String name, String tin, String status) {
        requireAdmin();
        Map<String, Object> before = lock("organizations", id, version, "Organisation");
        if ("INACTIVE".equals(status) && count("SELECT count(*) FROM offices WHERE organization_id = ? AND status = 'ACTIVE'", id) > 0) {
            throw state("Deactivate the organisation's offices first.");
        }
        jdbc.update("UPDATE organizations SET name = ?, tin = ?, status = ?, version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?",
                name.trim(), tin == null || tin.isBlank() ? null : tin.trim(), status(status), now(), CurrentActor.username(), id);
        audit.record("ORGANIZATION_UPDATED", "ORGANIZATION", id, Map.of("before", Map.of("name", before.get("name"), "status", before.get("status")),
                "after", Map.of("name", name.trim(), "status", status(status))));
    }

    @Transactional
    public UUID createOffice(UUID organizationId, String code, String name) {
        requireAdmin();
        String c = code(code);
        if (count("SELECT count(*) FROM organizations WHERE id = ? AND status = 'ACTIVE'", organizationId) == 0) {
            throw ApiException.notFound("Active organisation");
        }
        if (count("SELECT count(*) FROM offices WHERE organization_id = ? AND code = ?", organizationId, c) > 0) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "An office with this code exists.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO offices (id, organization_id, code, name, created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?)", id, organizationId, c,
                name.trim(), CurrentActor.username(), CurrentActor.username());
        audit.record("OFFICE_CREATED", "OFFICE", id, Map.of("code", c, "name", name.trim()));
        return id;
    }

    @Transactional
    public void updateOffice(UUID id, long version, String name, String status) {
        requireAdmin();
        Map<String, Object> before = lock("offices", id, version, "Office");
        if ("INACTIVE".equals(status)) {
            if (count("SELECT count(*) FROM departments WHERE office_id = ? AND status = 'ACTIVE'", id) > 0) {
                throw state("Deactivate the office's departments first.");
            }
            if (count("SELECT count(*) FROM user_office_assignments WHERE office_id = ? AND effective_to IS NULL", id) > 0) {
                throw state("Users are still assigned to this office; end or move their assignments first.");
            }
        }
        jdbc.update("UPDATE offices SET name = ?, status = ?, version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?", name.trim(),
                status(status), now(), CurrentActor.username(), id);
        audit.record("OFFICE_UPDATED", "OFFICE", id, Map.of("before", Map.of("name", before.get("name"), "status", before.get("status")),
                "after", Map.of("name", name.trim(), "status", status(status))));
    }

    @Transactional
    public UUID createDepartment(UUID officeId, String code, String name, UUID costCenterId) {
        requireAdmin();
        String c = code(code);
        if (count("SELECT count(*) FROM offices WHERE id = ? AND status = 'ACTIVE'", officeId) == 0) {
            throw ApiException.notFound("Active office");
        }
        if (count("SELECT count(*) FROM departments WHERE office_id = ? AND code = ?", officeId, c) > 0) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A department with this code exists in the office.");
        }
        requireCostCenter(costCenterId);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, office_id, code, name, cost_center_id, created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, officeId, c, name.trim(), costCenterId, CurrentActor.username(), CurrentActor.username());
        audit.record("DEPARTMENT_CREATED", "DEPARTMENT", id, Map.of("code", c, "name", name.trim(), "costCenter", String.valueOf(costCenterId)));
        return id;
    }

    @Transactional
    public void updateDepartment(UUID id, long version, String name, UUID costCenterId, String status) {
        requireAdmin();
        Map<String, Object> before = lock("departments", id, version, "Department");
        if ("INACTIVE".equals(status) && count("SELECT count(*) FROM user_office_assignments WHERE department_id = ? AND effective_to IS NULL", id) > 0) {
            throw state("Users are still assigned to this department; end or move their assignments first.");
        }
        requireCostCenter(costCenterId);
        jdbc.update("UPDATE departments SET name = ?, cost_center_id = ?, status = ?, version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?",
                name.trim(), costCenterId, status(status), now(), CurrentActor.username(), id);
        audit.record("DEPARTMENT_UPDATED", "DEPARTMENT", id, Map.of(
                "before", Map.of("name", before.get("name"), "status", before.get("status"), "costCenter", String.valueOf(before.get("cost_center_id"))),
                "after", Map.of("name", name.trim(), "status", status(status), "costCenter", String.valueOf(costCenterId))));
    }

    // ---------------------------------------------------------------- assignments

    @Transactional(readOnly = true)
    public List<Assignment> assignments(UUID userId) {
        if (!CurrentActor.hasPermission("USER_VIEW") && !CurrentActor.hasPermission("ORGANIZATION_ADMINISTER")
                && !userId.equals(CurrentActor.userId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot view this user's assignments.");
        }
        return jdbc.query("""
                SELECT a.*, u.username, o.code AS o_code, o.name AS o_name, d.code AS d_code, d.name AS d_name
                FROM user_office_assignments a JOIN users u ON u.id = a.user_id JOIN offices o ON o.id = a.office_id
                LEFT JOIN departments d ON d.id = a.department_id WHERE a.user_id = ? ORDER BY a.effective_from DESC, a.created_at DESC""",
                (rs, i) -> new Assignment(rs.getObject("id", UUID.class), userId, rs.getString("username"), rs.getObject("office_id", UUID.class),
                        rs.getString("o_code"), rs.getString("o_name"), rs.getObject("department_id", UUID.class), rs.getString("d_code"),
                        rs.getString("d_name"), rs.getDate("effective_from").toLocalDate(),
                        rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(), rs.getString("created_by"),
                        rs.getString("ended_by")), userId);
    }

    /** Assigns the user to an office (and optionally a department) from a date, closing the current assignment the day before. */
    @Transactional
    public List<Assignment> assign(UUID userId, UUID officeId, UUID departmentId, LocalDate from) {
        requireAdmin();
        Map<String, Object> user = jdbc.queryForList("SELECT user_type, status FROM users WHERE id = ? FOR UPDATE", userId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("User"));
        if (!"INTERNAL".equals(user.get("user_type"))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Only internal users have office assignments.");
        }
        if (count("SELECT count(*) FROM offices WHERE id = ? AND status = 'ACTIVE'", officeId) == 0) {
            throw ApiException.notFound("Active office");
        }
        if (departmentId != null && count("SELECT count(*) FROM departments WHERE id = ? AND office_id = ? AND status = 'ACTIVE'", departmentId,
                officeId) == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The department is not an active department of that office.");
        }
        List<Map<String, Object>> current = jdbc.queryForList(
                "SELECT id, effective_from FROM user_office_assignments WHERE user_id = ? AND effective_to IS NULL", userId);
        if (!current.isEmpty()) {
            LocalDate since = ((Date) current.get(0).get("effective_from")).toLocalDate();
            if (!from.isAfter(since)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "The new assignment must start after the current one (" + since + ").");
            }
            jdbc.update("UPDATE user_office_assignments SET effective_to = ?, ended_by = ? WHERE id = ?", Date.valueOf(from.minusDays(1)),
                    CurrentActor.username(), current.get(0).get("id"));
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_office_assignments (id, user_id, office_id, department_id, effective_from, created_by)
                VALUES (?, ?, ?, ?, ?, ?)""", id, userId, officeId, departmentId, Date.valueOf(from), CurrentActor.username());
        audit.record("USER_OFFICE_ASSIGNED", "USER", userId, Map.of("office", officeId.toString(), "department", String.valueOf(departmentId),
                "from", from.toString(), "previousClosed", !current.isEmpty()));
        return assignments(userId);
    }

    @Transactional
    public List<Assignment> end(UUID assignmentId, LocalDate to) {
        requireAdmin();
        Map<String, Object> a = jdbc.queryForList("SELECT * FROM user_office_assignments WHERE id = ? FOR UPDATE", assignmentId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Assignment"));
        if (a.get("effective_to") != null) {
            throw state("The assignment has already ended.");
        }
        if (to.isBefore(((Date) a.get("effective_from")).toLocalDate())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The end date cannot be before the start date.");
        }
        jdbc.update("UPDATE user_office_assignments SET effective_to = ?, ended_by = ? WHERE id = ?", Date.valueOf(to), CurrentActor.username(),
                assignmentId);
        audit.record("USER_OFFICE_ASSIGNMENT_ENDED", "USER", a.get("user_id"), Map.of("assignment", assignmentId.toString(), "to", to.toString()));
        return assignments((UUID) a.get("user_id"));
    }

    // ---------------------------------------------------------------- helpers

    private static Department department(Map<String, Object> d) {
        return new Department((UUID) d.get("id"), (UUID) d.get("office_id"), (String) d.get("code"), (String) d.get("name"), (String) d.get("status"),
                (UUID) d.get("cost_center_id"), (String) d.get("cc_code"), ((Number) d.get("current_users")).longValue(),
                ((Number) d.get("version")).longValue());
    }

    private Map<String, Object> lock(String table, UUID id, long version, String what) {
        Map<String, Object> r = jdbc.queryForList("SELECT * FROM " + table + " WHERE id = ? FOR UPDATE", id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound(what));
        if (((Number) r.get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        return r;
    }

    private void requireCostCenter(UUID costCenterId) {
        if (costCenterId != null && count("SELECT count(*) FROM cost_centers WHERE id = ?", costCenterId) == 0) {
            throw ApiException.notFound("Cost centre");
        }
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static void requireAdmin() {
        CurrentActor.requirePermission("ORGANIZATION_ADMINISTER");
    }

    private static String code(String code) {
        String c = code == null ? "" : code.trim().toUpperCase();
        if (!CODE.matcher(c).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Codes are 2-20 letters, digits, '-' or '_'.");
        }
        return c;
    }

    private static String status(String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Status must be ACTIVE or INACTIVE.");
        }
        return status;
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now(clock));
    }

    private static ApiException state(String message) {
        return new ApiException(ErrorCode.INVALID_STATE, message);
    }
}
