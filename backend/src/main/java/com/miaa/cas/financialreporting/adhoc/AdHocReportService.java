package com.miaa.cas.financialreporting.adhoc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.financialreporting.export.ReportTable;
import com.miaa.cas.financialreporting.adhoc.AdHocDatasets.Column;
import com.miaa.cas.financialreporting.adhoc.AdHocDatasets.Dataset;
import com.miaa.cas.financialreporting.adhoc.AdHocDatasets.Type;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ad hoc reports (TOR Annex B.5). A definition names a dataset ({@link AdHocDatasets}), the columns to show, filters,
 * optional grouping (amounts are then summed and rows counted), the order and a row limit. The query is assembled only
 * from the dataset's own column expressions and fixed operators; every value is a bound parameter. Definitions can be
 * saved and shared with everyone who may see the dataset; they export as CSV, XLSX or PDF through the report job.
 */
@Service
public class AdHocReportService {

    public static final int PREVIEW_ROWS = 200;
    public static final int MAX_ROWS = 100_000;
    static final Set<String> TEXT_OPS = Set.of("EQ", "NE", "CONTAINS", "STARTS_WITH");
    static final Set<String> VALUE_OPS = Set.of("EQ", "NE", "GT", "GE", "LT", "LE", "BETWEEN");

    public record Filter(String column, String op, String value, String value2) {
    }

    public record Sort(String column, boolean descending) {
    }

    public record Definition(List<String> columns, List<Filter> filters, List<String> groupBy, List<Sort> sort, Integer limit) {
    }

    public record ColumnInfo(String key, String label, String type) {
    }

    public record DatasetInfo(String code, String label, List<ColumnInfo> columns) {
    }

    public record Result(List<ColumnInfo> columns, List<List<Object>> rows, List<Object> totals, boolean truncated) {
    }

    public record SavedView(UUID id, String name, String dataset, Definition definition, boolean shared, String owner, boolean mine,
            Instant updatedAt, long version) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final Clock clock;

    public AdHocReportService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    /** The permission needed to use a dataset (and to request or download its exports). */
    public static String permissionFor(String dataset) {
        Dataset d = AdHocDatasets.ALL.get(dataset);
        return d == null ? "FINANCIAL_REPORT_EXPORT" : d.permission();
    }

    /** The datasets the caller may use. */
    public List<DatasetInfo> datasets() {
        return AdHocDatasets.ALL.values().stream().filter(d -> CurrentActor.hasPermission(d.permission()))
                .map(d -> new DatasetInfo(d.code(), d.label(), d.columns().values().stream()
                        .map(c -> new ColumnInfo(c.key(), c.label(), c.type().name())).toList())).toList();
    }

    @Transactional(readOnly = true)
    public Result preview(String dataset, Definition def) {
        return run(dataset, def, PREVIEW_ROWS, true);
    }

    // ---------------------------------------------------------------- saved definitions

    @Transactional(readOnly = true)
    public List<SavedView> saved() {
        UUID me = CurrentActor.userId();
        return jdbc.query("SELECT * FROM report_definitions WHERE owner_id = ? OR shared ORDER BY name", (rs, i) -> view(rs, me), me).stream()
                .filter(v -> CurrentActor.hasPermission(permissionFor(v.dataset()))).toList();
    }

    @Transactional
    public SavedView save(UUID id, Long version, String name, String dataset, Definition def, boolean shared) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 3 || n.length() > 120) {
            throw bad("Name the report (3 to 120 characters).");
        }
        sql(dataset, def, 1, new ArrayList<>()); // validates
        Timestamp now = Timestamp.from(Instant.now(clock));
        String body = write(def);
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.update("INSERT INTO report_definitions (id, name, dataset, definition, shared, owner_id, owner, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)", id, n, dataset, body, shared, CurrentActor.userId(), CurrentActor.username(), now, now);
            audit.record("AD_HOC_REPORT_SAVED", "REPORT_DEFINITION", id, Map.of("name", n, "dataset", dataset, "shared", shared));
        } else {
            int updated = jdbc.update("UPDATE report_definitions SET name = ?, dataset = ?, definition = ?::jsonb, shared = ?, updated_at = ?, "
                    + "version = version + 1 WHERE id = ? AND owner_id = ? AND version = ?", n, dataset, body, shared, now, id,
                    CurrentActor.userId(), version == null ? Long.valueOf(-1) : version);
            if (updated == 0) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The report was changed elsewhere, or is not yours to change.");
            }
            audit.record("AD_HOC_REPORT_UPDATED", "REPORT_DEFINITION", id, Map.of("name", n, "shared", shared));
        }
        UUID saved = id;
        return jdbc.queryForObject("SELECT * FROM report_definitions WHERE id = ?", (rs, i) -> view(rs, CurrentActor.userId()), saved);
    }

    @Transactional
    public void delete(UUID id) {
        if (jdbc.update("DELETE FROM report_definitions WHERE id = ? AND owner_id = ?", id, CurrentActor.userId()) == 0) {
            throw ApiException.notFound("Report");
        }
        audit.record("AD_HOC_REPORT_DELETED", "REPORT_DEFINITION", id, Map.of());
    }

    /** A saved report the caller may run: their own or shared, with the dataset's permission. */
    @Transactional(readOnly = true)
    public SavedView load(UUID id) {
        UUID me = CurrentActor.userId();
        List<SavedView> v = jdbc.query("SELECT * FROM report_definitions WHERE id = ? AND (owner_id = ? OR shared)", (rs, i) -> view(rs, me), id, me);
        if (v.isEmpty() || !CurrentActor.hasPermission(permissionFor(v.get(0).dataset()))) {
            throw ApiException.notFound("Report");
        }
        return v.get(0);
    }

    // ---------------------------------------------------------------- report jobs

    /** Checks a job's parameters: a saved report the requester may run, of the dataset named. */
    public void validateJob(Map<String, String> p) {
        String id = p.get("definitionId");
        if (id == null) {
            throw bad("parameters.definitionId is required.");
        }
        SavedView v;
        try {
            v = load(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            throw bad("parameters.definitionId is not valid.");
        }
        if (!v.dataset().equals(p.get("dataset"))) {
            throw bad("parameters.dataset must be the report's dataset, " + v.dataset() + ".");
        }
    }

    /** Renders a saved report for the job worker (no signed-in user: the request was checked when it was queued). */
    @Transactional(readOnly = true)
    public ReportTable render(Map<String, String> p, String by, String at) {
        Map<String, Object> row = jdbc.queryForMap("SELECT name, dataset, definition::text AS definition FROM report_definitions WHERE id = ?",
                UUID.fromString(p.get("definitionId")));
        Definition def = read((String) row.get("definition"));
        Result r = run((String) row.get("dataset"), def, Math.min(def.limit() == null ? MAX_ROWS : def.limit(), MAX_ROWS), false);
        List<ReportTable.Row> rows = new ArrayList<>();
        for (List<Object> cells : r.rows()) {
            rows.add(new ReportTable.Row(cells.get(0) == null ? "" : cells.get(0).toString(), 0, false, cells.subList(1, cells.size())));
        }
        if (r.totals().stream().anyMatch(t -> t != null)) {
            rows.add(new ReportTable.Row("Total", 0, true, r.totals().subList(1, r.totals().size())));
        }
        List<String> notes = new ArrayList<>();
        notes.add("Ad hoc report on " + AdHocDatasets.ALL.get(row.get("dataset")).label().toLowerCase() + describe(def) + ".");
        if (r.truncated()) {
            notes.add("Only the first " + rows.size() + " rows are included.");
        }
        notes.add("Generated " + at + " by " + by + ".");
        return new ReportTable((String) row.get("name"), List.of("Avtech Information Technology Solutions, Inc."), r.columns().get(0).label(),
                r.columns().subList(1, r.columns().size()).stream().map(ColumnInfo::label).toList(), rows, notes);
    }

    // ---------------------------------------------------------------- query

    private Result run(String dataset, Definition def, int limit, boolean checkPermission) {
        List<Object> args = new ArrayList<>();
        List<ColumnInfo> cols = new ArrayList<>();
        String sql = sql(dataset, def, limit + 1, args, cols, checkPermission);
        List<List<Object>> rows = jdbc.query(sql, (rs, i) -> {
            List<Object> cells = new ArrayList<>();
            for (int c = 1; c <= cols.size(); c++) {
                Object v = rs.getObject(c);
                cells.add(v instanceof java.sql.Date d ? d.toLocalDate().toString() : v instanceof Long n ? BigDecimal.valueOf(n) : v);
            }
            return cells;
        }, args.toArray());
        boolean truncated = rows.size() > limit;
        if (truncated) {
            rows = rows.subList(0, limit);
        }
        List<Object> totals = new ArrayList<>();
        for (int c = 0; c < cols.size(); c++) {
            if ("AMOUNT".equals(cols.get(c).type()) || "COUNT".equals(cols.get(c).type())) {
                BigDecimal t = BigDecimal.ZERO;
                for (List<Object> r : rows) {
                    if (r.get(c) instanceof BigDecimal b) {
                        t = t.add(b);
                    }
                }
                totals.add(t);
            } else {
                totals.add(null);
            }
        }
        return new Result(cols, rows, totals, truncated);
    }

    private String sql(String dataset, Definition def, int limit, List<Object> args) {
        return sql(dataset, def, limit, args, new ArrayList<>(), true);
    }

    String sql(String dataset, Definition def, int limit, List<Object> args, List<ColumnInfo> cols, boolean checkPermission) {
        Dataset d = AdHocDatasets.ALL.get(dataset);
        if (d == null) {
            throw bad("Unknown dataset " + dataset + ".");
        }
        if (checkPermission) {
            CurrentActor.requirePermission(d.permission());
        }
        if (def == null || def.columns() == null || def.columns().isEmpty()) {
            throw bad("Choose at least one column.");
        }
        List<String> group = def.groupBy() == null ? List.of() : def.groupBy();
        boolean grouped = !group.isEmpty();
        List<String> select = new ArrayList<>();
        for (String key : def.columns()) {
            Column c = column(d, key);
            if (grouped && !group.contains(key) && c.type() != Type.AMOUNT && c.type() != Type.NUMBER) {
                throw bad("When grouping, " + c.label() + " must be one of the grouping columns.");
            }
            select.add(grouped && !group.contains(key) ? "sum(" + c.sql() + ")" : c.sql());
            cols.add(new ColumnInfo(c.key(), grouped && !group.contains(key) ? "Total " + c.label().toLowerCase() : c.label(), c.type().name()));
        }
        if (grouped) {
            for (String g : group) {
                if (!def.columns().contains(g)) {
                    throw bad("Show every grouping column (" + column(d, g).label() + ").");
                }
            }
            select.add("count(*)");
            cols.add(new ColumnInfo("count", "Rows", "COUNT"));
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", select)).append(" FROM ").append(d.from()).append(" WHERE TRUE");
        for (Filter f : def.filters() == null ? List.<Filter>of() : def.filters()) {
            Column c = column(d, f.column());
            String op = f.op() == null ? "" : f.op();
            Set<String> allowed = c.type() == Type.TEXT ? TEXT_OPS : VALUE_OPS;
            if (!allowed.contains(op)) {
                throw bad(c.label() + ": the condition must be one of " + allowed + ".");
            }
            switch (op) {
                case "CONTAINS" -> {
                    sql.append(" AND lower(").append(c.sql()).append(") LIKE ?");
                    args.add("%" + like(f.value()) + "%");
                }
                case "STARTS_WITH" -> {
                    sql.append(" AND lower(").append(c.sql()).append(") LIKE ?");
                    args.add(like(f.value()) + "%");
                }
                case "BETWEEN" -> {
                    sql.append(" AND ").append(c.sql()).append(" BETWEEN ? AND ?");
                    args.add(value(c, f.value()));
                    args.add(value(c, f.value2()));
                }
                default -> {
                    String symbol = Map.of("EQ", "=", "NE", "<>", "GT", ">", "GE", ">=", "LT", "<", "LE", "<=").get(op);
                    sql.append(" AND ").append(c.sql()).append(' ').append(symbol).append(" ?");
                    args.add(value(c, f.value()));
                }
            }
        }
        if (grouped) {
            sql.append(" GROUP BY ").append(String.join(", ", group.stream().map(g -> column(d, g).sql()).toList()));
        }
        List<String> order = new ArrayList<>();
        for (Sort s : def.sort() == null ? List.<Sort>of() : def.sort()) {
            int index = def.columns().indexOf(s.column());
            if (index < 0) {
                throw bad("Sort only by a column that is shown.");
            }
            order.add((index + 1) + (s.descending() ? " DESC" : " ASC"));
        }
        if (order.isEmpty()) {
            order.add("1");
        }
        sql.append(" ORDER BY ").append(String.join(", ", order)).append(" NULLS LAST LIMIT ?");
        int max = Math.min(def.limit() == null ? MAX_ROWS : Math.max(1, def.limit()), MAX_ROWS);
        args.add(Math.min(limit, max + 1));
        return sql.toString();
    }

    private static Column column(Dataset d, String key) {
        Column c = key == null ? null : d.columns().get(key);
        if (c == null) {
            throw bad("Unknown column " + key + " in " + d.label() + ".");
        }
        return c;
    }

    private static Object value(Column c, String v) {
        if (v == null || v.isBlank()) {
            throw bad(c.label() + ": enter a value.");
        }
        try {
            return switch (c.type()) {
                case DATE -> java.sql.Date.valueOf(LocalDate.parse(v.trim()));
                case AMOUNT, NUMBER -> new BigDecimal(v.trim().replace(",", ""));
                case TEXT -> v.trim();
            };
        } catch (DateTimeParseException | NumberFormatException e) {
            throw bad(c.label() + ": \"" + v + "\" is not a " + (c.type() == Type.DATE ? "date (YYYY-MM-DD)" : "number") + ".");
        }
    }

    private static String like(String v) {
        if (v == null || v.isBlank()) {
            throw bad("Enter the text to look for.");
        }
        return v.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String describe(Definition def) {
        List<String> parts = new ArrayList<>();
        if (def.filters() != null) {
            def.filters().forEach(f -> parts.add(f.column() + " " + f.op().toLowerCase().replace('_', ' ') + " " + f.value()
                    + (f.value2() == null ? "" : " and " + f.value2())));
        }
        String g = def.groupBy() == null || def.groupBy().isEmpty() ? "" : ", grouped by " + String.join(", ", def.groupBy());
        return (parts.isEmpty() ? "" : " where " + String.join("; ", parts)) + g;
    }

    private SavedView view(java.sql.ResultSet rs, UUID me) throws java.sql.SQLException {
        UUID owner = rs.getObject("owner_id", UUID.class);
        return new SavedView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("dataset"), read(rs.getString("definition")),
                rs.getBoolean("shared"), rs.getString("owner"), owner.equals(me), rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }

    private Definition read(String s) {
        try {
            return json.readValue(s, Definition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Definition d) {
        try {
            return json.writeValueAsString(d);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
