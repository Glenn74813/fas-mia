package com.miaa.cas.financialreporting;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.financialreporting.ReportDtos.TemplateLine;
import com.miaa.cas.financialreporting.ReportDtos.TemplateLineRequest;
import com.miaa.cas.financialreporting.ReportDtos.TemplateRequest;
import com.miaa.cas.financialreporting.ReportDtos.TemplateView;
import com.miaa.cas.security.CurrentActor;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statement templates are versioned configuration (C-07): each version maps the chart's statement line
 * codes to presentation lines. A new version is a draft until a second administrator activates it,
 * which retires the previous active version; history is kept.
 */
@Service
public class StatementTemplateService {

    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public StatementTemplateService(AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TemplateView> list() {
        return jdbc.queryForList("SELECT id FROM report_templates ORDER BY code, version DESC", UUID.class).stream().map(this::get).toList();
    }

    @Transactional(readOnly = true)
    public TemplateView get(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM report_templates WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Statement template");
        }
        Map<String, Object> t = rows.get(0);
        List<TemplateLine> lines = jdbc.query("SELECT * FROM report_template_lines WHERE template_id = ? ORDER BY line_no",
                (rs, i) -> new TemplateLine(rs.getInt("line_no"), rs.getString("code"), rs.getString("label"), rs.getString("line_type"),
                        strings(rs.getArray("fs_line_codes")), strings(rs.getArray("total_of")), rs.getInt("sign"), rs.getInt("indent"),
                        strings(rs.getArray("counterpart_codes"))), id);
        List<String> actions = new ArrayList<>();
        boolean admin = hasActor() && CurrentActor.hasPermission("ACCOUNT_ADMINISTER");
        if ("DRAFT".equals(t.get("status")) && admin && !CurrentActor.userId().equals(t.get("created_by_id"))) {
            actions.add("ACTIVATE");
        }
        Timestamp act = (Timestamp) t.get("activated_at");
        return new TemplateView(id, (String) t.get("code"), ((Number) t.get("version")).intValue(), (String) t.get("name"),
                (String) t.get("statement_type"), (String) t.get("status"), (Boolean) t.get("placeholder"), (String) t.get("balance_check"),
                (String) t.get("created_by"), ((Timestamp) t.get("created_at")).toInstant(), (String) t.get("activated_by"),
                act == null ? null : act.toInstant(), lines, actions);
    }

    /** The active version of a template code, or the given id when it names a specific version. */
    UUID resolve(String idOrCode) {
        try {
            UUID id = UUID.fromString(idOrCode);
            get(id);
            return id;
        } catch (IllegalArgumentException e) {
            List<UUID> ids = jdbc.queryForList("SELECT id FROM report_templates WHERE code = ? AND status = 'ACTIVE'", UUID.class, idOrCode);
            if (ids.isEmpty()) {
                throw ApiException.notFound("Active statement template " + idOrCode);
            }
            return ids.get(0);
        }
    }

    @Transactional
    public TemplateView create(TemplateRequest req, boolean placeholder, Actor actor) {
        validate(req);
        int max = jdbc.queryForObject("SELECT coalesce(max(version), 0) FROM report_templates WHERE code = ?", Integer.class, req.code());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO report_templates (id, code, version, name, statement_type, status, placeholder, balance_check, created_by_id,
                    created_by, created_at) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)""", id, req.code(), max + 1, req.name().trim(),
                req.statementType(), placeholder, blank(req.balanceCheck()), actor.userId(), actor.username(), Timestamp.from(Instant.now(clock)));
        int n = 1;
        for (TemplateLineRequest l : req.lines()) {
            jdbc.update("INSERT INTO report_template_lines (template_id, line_no, code, label, line_type, fs_line_codes, total_of, sign, indent, "
                    + "counterpart_codes) VALUES (?, ?, ?, ?, ?, ?::varchar[], ?::varchar[], ?, ?, ?::varchar[])", id, n++, l.code(), l.label().trim(),
                    l.type(), pgArray(l.fsLineCodes()), pgArray(l.totalOf()), l.sign(), java.util.Objects.requireNonNullElse(l.indent(), 0),
                    pgArray(l.counterpartCodes()));
        }
        audit.record(actor, "REPORT_TEMPLATE_CREATED", "REPORT_TEMPLATE", req.code() + " v" + (max + 1), Map.of("lines", req.lines().size(),
                "placeholder", placeholder));
        return get(id);
    }

    @Transactional
    public TemplateView activate(UUID id, Actor actor) {
        Map<String, Object> t = jdbc.queryForMap("SELECT * FROM report_templates WHERE id = ? FOR UPDATE", id);
        if (!"DRAFT".equals(t.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only draft templates can be activated.");
        }
        if (actor.userId() != null && actor.userId().equals(t.get("created_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "A second administrator must activate the template you defined.");
        }
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("UPDATE report_templates SET status = 'RETIRED' WHERE code = ? AND status = 'ACTIVE'", t.get("code"));
        jdbc.update("UPDATE report_templates SET status = 'ACTIVE', activated_by = ?, activated_at = ? WHERE id = ?", actor.username(), now, id);
        audit.record(actor, "REPORT_TEMPLATE_ACTIVATED", "REPORT_TEMPLATE", t.get("code") + " v" + t.get("version"), Map.of());
        return get(id);
    }

    static final Set<String> MOVEMENT_STATEMENTS = Set.of("SCF", "SCE");

    private static void validate(TemplateRequest req) {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> fsCodes = new HashSet<>();
        // Per group of movement accounts: the counterpart codes already classified, and whether a line takes the whole change.
        Map<Set<String>, Set<String>> classified = new java.util.HashMap<>();
        Set<Set<String>> wholeChange = new HashSet<>();
        boolean surplus = false;
        boolean movement = MOVEMENT_STATEMENTS.contains(req.statementType());
        for (TemplateLineRequest l : req.lines()) {
            if (!seen.add(l.code())) {
                problems.add("Line code " + l.code() + " is used twice.");
            }
            List<String> fs = l.fsLineCodes() == null ? List.of() : l.fsLineCodes();
            List<String> tot = l.totalOf() == null ? List.of() : l.totalOf();
            List<String> cp = l.counterpartCodes() == null ? List.of() : l.counterpartCodes();
            if (!cp.isEmpty() && !"MOVEMENT".equals(l.type())) {
                problems.add("Line " + l.code() + ": only MOVEMENT lines have counterpart codes.");
            }
            if (movement && "LINE".equals(l.type())) {
                problems.add("Line " + l.code() + ": a " + req.statementType() + " statement uses OPENING, CLOSING and MOVEMENT lines, not LINE.");
            }
            if (!movement && Set.of("OPENING", "CLOSING", "MOVEMENT").contains(l.type())) {
                problems.add("Line " + l.code() + ": " + l.type() + " lines belong to movement statements (SCF, SCE).");
            }
            switch (l.type()) {
                case "OPENING", "CLOSING" -> {
                    if (fs.isEmpty()) {
                        problems.add("Line " + l.code() + " needs the statement line codes whose balance it shows.");
                    }
                }
                case "MOVEMENT" -> {
                    if (fs.isEmpty()) {
                        problems.add("Movement " + l.code() + " needs the statement line codes whose change it shows.");
                    }
                    Set<String> group = Set.copyOf(fs);
                    for (String c : cp) {
                        if (group.contains(c)) {
                            problems.add("Movement " + l.code() + ": counterpart code " + c + " is one of its own codes.");
                        }
                    }
                    if (cp.isEmpty()) {
                        if (!wholeChange.add(group) || classified.containsKey(group)) {
                            problems.add("Movement " + l.code() + " takes the whole change of " + fs
                                    + ", which another movement line already shows (it would be counted twice).");
                        }
                    } else {
                        if (cp.contains("*") && cp.size() > 1) {
                            problems.add("Movement " + l.code() + ": '*' (everything else) stands alone.");
                        }
                        if (wholeChange.contains(group)) {
                            problems.add("Movement " + l.code() + " classifies part of a change another line shows whole (counted twice).");
                        }
                        Set<String> done = classified.computeIfAbsent(group, k -> new HashSet<>());
                        for (String c : cp) {
                            if (!done.add(c)) {
                                problems.add("Counterpart code " + c + " is classified by more than one movement of " + fs + " (counted twice).");
                            }
                        }
                    }
                }
                case "LINE" -> {
                    if (fs.isEmpty()) {
                        problems.add("Line " + l.code() + " needs at least one statement line code.");
                    }
                    for (String c : fs) {
                        if (!fsCodes.add(c)) {
                            problems.add("Statement line code " + c + " is mapped to more than one line (it would be counted twice).");
                        }
                    }
                }
                case "TOTAL" -> {
                    if (tot.isEmpty()) {
                        problems.add("Total " + l.code() + " must list the lines it adds.");
                    }
                    for (String raw : tot) {
                        String c = raw.startsWith("-") ? raw.substring(1) : raw;
                        if (!seen.contains(c) || c.equals(l.code())) {
                            problems.add("Total " + l.code() + " refers to " + c + ", which is not an earlier line.");
                        }
                    }
                }
                case "SURPLUS" -> {
                    if (!"SFP".equals(req.statementType()) && !"SCE".equals(req.statementType())) {
                        problems.add("A surplus line belongs on the statement of financial position or of changes in equity.");
                    }
                    surplus = true;
                }
                default -> {
                    // headers carry no amount
                }
            }
        }
        if ("SFP".equals(req.statementType()) && !surplus) {
            problems.add("The statement of financial position needs a SURPLUS line for results not yet closed to equity.");
        }
        if (req.balanceCheck() != null && !req.balanceCheck().isBlank()) {
            String[] p = req.balanceCheck().split("=");
            if (p.length != 2 || !seen.contains(p[0].trim()) || !seen.contains(p[1].trim())) {
                problems.add("balanceCheck must be 'LINE_A=LINE_B' naming two lines of the template.");
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, problems.get(0), Map.of("violations", problems));
        }
    }

    static List<String> strings(Array a) throws java.sql.SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    static String pgArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", values.stream().map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()) + "}";
    }

    private static boolean hasActor() {
        try {
            CurrentActor.principal();
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
