package com.miaa.cas.common.web;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Database-side paging for JDBC list queries. {@code sql} is a complete, deterministically ordered query without LIMIT;
 * the total comes from counting the same query, and the page from LIMIT/OFFSET on it.
 */
public final class JdbcPaging {

    public static final int DEFAULT_SIZE = 25;

    private JdbcPaging() {
    }

    public static void validate(int page, int size) {
        if (page < 0 || size < 1 || size > PageRequests.MAX_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "page must be >= 0 and size between 1 and " + PageRequests.MAX_SIZE);
        }
    }

    public static <T> PageResponse<T> query(JdbcTemplate jdbc, String sql, RowMapper<T> mapper, int page, int size, Object... args) {
        validate(page, size);
        long total = jdbc.queryForObject("SELECT count(*) FROM (" + sql + ") AS paged", Long.class, args);
        Object[] withWindow = Arrays.copyOf(args, args.length + 2);
        withWindow[args.length] = size;
        withWindow[args.length + 1] = (long) page * size;
        List<T> items = jdbc.query(sql + " LIMIT ? OFFSET ?", mapper, withWindow);
        return new PageResponse<>(items, page, size, total, (int) ((total + size - 1) / size));
    }

    /** Pages a query whose single column is the row id; map the ids to views with {@link PageResponse#map}. */
    public static PageResponse<UUID> ids(JdbcTemplate jdbc, String sql, int page, int size, Object... args) {
        return query(jdbc, sql, (rs, i) -> rs.getObject(1, UUID.class), page, size, args);
    }

    /** A LIKE pattern for a free-text search (lower-cased, wildcards escaped), or null when there is nothing to search. */
    public static String like(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /**
     * An ORDER BY clause for a public sort key ({@code name}, or {@code -name} for descending) from a whitelist of SQL
     * expressions, with the id as tie-breaker. Unknown keys are refused, so the request never reaches the SQL text.
     */
    public static String orderBy(String sort, java.util.Map<String, String> columns, String defaultSort) {
        String requested = sort == null || sort.isBlank() ? defaultSort : sort.trim();
        boolean desc = requested.startsWith("-");
        String key = desc ? requested.substring(1) : requested;
        String column = columns.get(key);
        if (column == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported sort key '" + key + "'. Allowed: "
                    + String.join(", ", new java.util.TreeSet<>(columns.keySet())));
        }
        return column + (desc ? " DESC" : " ASC") + " NULLS LAST, id";
    }
}
