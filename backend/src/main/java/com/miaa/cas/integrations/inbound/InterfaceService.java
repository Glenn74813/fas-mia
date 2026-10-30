package com.miaa.cas.integrations.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.budgeting.BudgetDtos.AllocationRequest;
import com.miaa.cas.budgeting.BudgetDtos.BudgetView;
import com.miaa.cas.budgeting.BudgetService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.fixedassets.FaDtos.AssetRequest;
import com.miaa.cas.fixedassets.FixedAssetService;
import com.miaa.cas.journal.JournalVoucherService;
import com.miaa.cas.journal.web.JournalDtos.LineRequest;
import com.miaa.cas.journal.web.JournalDtos.VoucherRequest;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.security.client.ApiClientAuthenticator;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbound interfaces (TOR §4.6.2, Annex D). A file is validated into a batch; accepting it creates drafts through the
 * same services a person would use, so every rule, approval and audit of those documents applies. Field layouts are
 * proposals until AVTECH supplies each system's (C-37):
 * <ul>
 * <li>PAYROLL, a payroll summary: header {@code reference}, {@code accountingDate} (or {@code period} YYYY-MM, its
 * last day) and {@code description}; rows {@code accountCode}, {@code costCenterCode}, {@code description},
 * {@code debit}, {@code credit}. Accepting creates one draft journal voucher.</li>
 * <li>FMIS_BUDGET, budget allotments: header {@code reference}, {@code fiscalYear}; rows {@code accountCode},
 * {@code costCenterCode}, {@code amount}. Accepting creates the year's budget if there is none and sets its draft
 * allocations to the file's.</li>
 * <li>ASSETS, asset information: header {@code reference}, {@code offsetAccountCode}; rows {@code categoryCode},
 * {@code description}, {@code serialNumber}, {@code location}, {@code custodian}, {@code costCenterCode},
 * {@code acquisitionDate}, {@code inServiceDate}, {@code cost}, {@code residualValue}, {@code usefulLifeMonths}.
 * Accepting creates draft fixed assets, acquired as OTHER against the offset account.</li>
 * </ul>
 */
@Service
public class InterfaceService {

    public static final String SUBMIT = "INTERFACE_SUBMIT";
    static final Map<String, String> PERMISSION = Map.of("PAYROLL", "JOURNAL_CREATE", "FMIS_BUDGET", "BUDGET_PREPARE", "ASSETS", "FA_MANAGE");

    public record BatchView(UUID id, String interfaceType, String format, String sourceReference, String fileSha256, String status, int rowCount,
            Map<String, String> header, List<Map<String, String>> rows, List<String> errors, String receivedBy, Instant receivedAt,
            String decidedBy, Instant decidedAt, String decisionNote, String resultType, List<UUID> resultIds, long version) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JournalVoucherService vouchers;
    private final BudgetService budgets;
    private final FixedAssetService assets;
    private final Clock clock;

    public InterfaceService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit, NotificationService notifications,
            JournalVoucherService vouchers, BudgetService budgets, FixedAssetService assets, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.notifications = notifications;
        this.vouchers = vouchers;
        this.budgets = budgets;
        this.assets = assets;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- receive

    @Transactional
    public BatchView submit(String type, String format, byte[] content, Map<String, String> requestHeader) {
        String perm = permission(type);
        boolean client = isApiClient();
        if (!(client ? CurrentActor.hasPermission(SUBMIT) : CurrentActor.hasPermission(perm))) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You may not send " + type.toLowerCase().replace('_', ' ') + " files.");
        }
        String sha = sha256(content);
        if (jdbc.queryForObject("SELECT count(*) FROM interface_batches WHERE interface = ? AND file_sha256 = ? AND status IN ('VALIDATED', "
                + "'ACCEPTED')", Long.class, type, sha) > 0) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "This file has already been received.");
        }
        Map<String, String> header = new LinkedHashMap<>();
        List<Map<String, String>> rows = List.of();
        List<String> errors = new ArrayList<>();
        try {
            InterfaceFileParser.Parsed p = InterfaceFileParser.parse(format, content, requestHeader, json);
            header = p.header();
            rows = p.rows();
            validate(type, header, rows, errors);
        } catch (InterfaceFileParser.ParseException e) {
            errors.add(e.getMessage());
        }
        String reference = header.getOrDefault("reference", "");
        if (reference.isBlank()) {
            errors.add(0, "The file needs a reference (a \"reference\" field, or the sourceReference parameter).");
        }
        UUID id = UUID.randomUUID();
        String status = errors.isEmpty() ? "VALIDATED" : "INVALID";
        jdbc.update("""
                INSERT INTO interface_batches (id, interface, format, source_reference, file_sha256, status, row_count, content, errors,
                    received_by_id, received_by, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""",
                id, type, format, reference.isBlank() ? "(none)" : truncate(reference, 100), sha, status, rows.size(),
                write(Map.of("header", header, "rows", rows)), write(errors.size() > 200 ? errors.subList(0, 200) : errors),
                client ? null : CurrentActor.userId(), CurrentActor.username(), Timestamp.from(Instant.now(clock)));
        audit.record("INTERFACE_BATCH_RECEIVED", "INTERFACE_BATCH", id, Map.of("interface", type, "format", format, "status", status,
                "rows", rows.size(), "sha256", sha));
        if (client && "VALIDATED".equals(status)) {
            for (UUID holder : jdbc.queryForList("""
                    SELECT DISTINCT u.id FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN role_permissions rp ON rp.role_id = ur.role_id
                    WHERE rp.permission_code = ? AND u.status = 'ACTIVE' AND u.user_type = 'INTERNAL'""", UUID.class, perm)) {
                notifications.notify(holder, "INTERFACE", "A " + type.toLowerCase().replace('_', ' ') + " file is waiting for review",
                        CurrentActor.username() + " sent " + rows.size() + " row(s), reference " + reference + ".", "/integrations/inbound");
            }
        }
        return get(id);
    }

    // ---------------------------------------------------------------- decide

    /** Creates the drafts. Runs the documents' own services, so their rules apply and any failure leaves nothing behind. */
    @Transactional
    public BatchView accept(UUID id, long version) {
        Map<String, Object> b = lock(id, version);
        String type = (String) b.get("interface");
        CurrentActor.requirePermission(permission(type));
        if (isApiClient()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "A person must accept an interface batch.");
        }
        if (!"VALIDATED".equals(b.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only a validated batch can be accepted.");
        }
        Map<String, Object> content = read((String) b.get("content"));
        @SuppressWarnings("unchecked")
        Map<String, String> header = (Map<String, String>) content.get("header");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) content.get("rows");
        String reference = (String) b.get("source_reference");
        List<UUID> results = new ArrayList<>();
        String resultType = switch (type) {
            case "PAYROLL" -> {
                List<LineRequest> lines = rows.stream().map(r -> new LineRequest(account(r.get("accountcode")), costCenter(r.get("costcentercode")),
                        null, blank(r.get("description")), amount(r.get("debit")), amount(r.get("credit")))).toList();
                String description = header.getOrDefault("description", "Payroll summary " + reference);
                results.add(vouchers.create(new VoucherRequest("STANDARD", payrollDate(header), false, truncate(description, 1000),
                        truncate("Payroll interface " + reference, 200), lines)).id());
                yield "JOURNAL_VOUCHER";
            }
            case "FMIS_BUDGET" -> {
                int year = Integer.parseInt(header.get("fiscalyear"));
                UUID fy = jdbc.queryForObject("SELECT id FROM fiscal_years WHERE fiscal_year = ?", UUID.class, year);
                List<UUID> existing = jdbc.queryForList("SELECT id FROM budgets WHERE fiscal_year_id = ?", UUID.class, fy);
                BudgetView budget = existing.isEmpty() ? budgets.create(fy, "Budget " + year + " (FMIS " + reference + ")")
                        : budgets.get(existing.get(0));
                List<AllocationRequest> allocations = rows.stream().map(r -> new AllocationRequest(account(r.get("accountcode")),
                        costCenter(r.get("costcentercode")), amount(r.get("amount")))).toList();
                results.add(budgets.setAllocations(budget.id(), budget.version(), allocations).id());
                yield "BUDGET";
            }
            default -> {
                UUID offset = account(header.get("offsetaccountcode"));
                for (Map<String, String> r : rows) {
                    results.add(assets.create(new AssetRequest(category(r.get("categorycode")), r.get("description"), blank(r.get("serialnumber")),
                            blank(r.get("location")), blank(r.get("custodian")), costCenter(r.get("costcentercode")), "OTHER", null, null, offset,
                            truncate("Imported from the asset system, batch " + reference, 1000), LocalDate.parse(r.get("acquisitiondate")),
                            LocalDate.parse(r.get("inservicedate")), amount(r.get("cost")),
                            r.get("residualvalue") == null || r.get("residualvalue").isBlank() ? null : amount(r.get("residualvalue")),
                            r.get("usefullifemonths") == null || r.get("usefullifemonths").isBlank() ? null
                                    : Integer.valueOf(r.get("usefullifemonths")))).id());
                }
                yield "FIXED_ASSET";
            }
        };
        jdbc.update("UPDATE interface_batches SET status = 'ACCEPTED', decided_by_id = ?, decided_by = ?, decided_at = ?, result_type = ?, "
                + "result_ids = ?, version = version + 1 WHERE id = ?", CurrentActor.userId(), CurrentActor.username(),
                Timestamp.from(Instant.now(clock)), resultType, results.toArray(UUID[]::new), id);
        audit.record("INTERFACE_BATCH_ACCEPTED", "INTERFACE_BATCH", id, Map.of("interface", type, "resultType", resultType,
                "results", results.size()));
        return get(id);
    }

    @Transactional
    public BatchView reject(UUID id, String note, long version) {
        Map<String, Object> b = lock(id, version);
        CurrentActor.requirePermission(permission((String) b.get("interface")));
        if (!"VALIDATED".equals(b.get("status")) && !"INVALID".equals(b.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The batch has already been " + b.get("status").toString().toLowerCase() + ".");
        }
        String n = note == null ? "" : note.trim();
        if (n.length() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give the reason for rejecting the batch.");
        }
        jdbc.update("UPDATE interface_batches SET status = 'REJECTED', decided_by_id = ?, decided_by = ?, decided_at = ?, decision_note = ?, "
                + "version = version + 1 WHERE id = ?", isApiClient() ? null : CurrentActor.userId(), CurrentActor.username(),
                Timestamp.from(Instant.now(clock)), truncate(n, 500), id);
        audit.record("INTERFACE_BATCH_REJECTED", "INTERFACE_BATCH", id, Map.of("note", n));
        return get(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<BatchView> list(String type, String status, int page, int size) {
        return JdbcPaging.query(jdbc, "SELECT * FROM interface_batches WHERE (?::text IS NULL OR interface = ?::text) "
                + "AND (?::text IS NULL OR status = ?::text) ORDER BY received_at DESC, id", (rs, i) -> map(rs, false), page, size,
                blank(type), blank(type), blank(status), blank(status));
    }

    @Transactional(readOnly = true)
    public BatchView get(UUID id) {
        List<BatchView> rows = jdbc.query("SELECT * FROM interface_batches WHERE id = ?", (rs, i) -> map(rs, true), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Interface batch");
        }
        return rows.get(0);
    }

    // ---------------------------------------------------------------- validation

    private void validate(String type, Map<String, String> header, List<Map<String, String>> rows, List<String> errors) {
        switch (type) {
            case "PAYROLL" -> {
                try {
                    payrollDate(header);
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                }
                BigDecimal debit = BigDecimal.ZERO;
                BigDecimal credit = BigDecimal.ZERO;
                for (int i = 0; i < rows.size(); i++) {
                    Map<String, String> r = rows.get(i);
                    String at = "Line " + (i + 1) + ": ";
                    checkAccount(r.get("accountcode"), at, errors, true);
                    checkCostCenter(r.get("costcentercode"), at, errors);
                    BigDecimal d = decimal(r.get("debit"), at + "debit", errors, true);
                    BigDecimal c = decimal(r.get("credit"), at + "credit", errors, true);
                    if (d != null && c != null) {
                        if ((d.signum() > 0) == (c.signum() > 0)) {
                            errors.add(at + "give either a debit or a credit.");
                        }
                        debit = debit.add(d);
                        credit = credit.add(c);
                    }
                }
                if (debit.compareTo(credit) != 0) {
                    errors.add("The lines do not balance: debits " + debit.toPlainString() + ", credits " + credit.toPlainString() + ".");
                }
            }
            case "FMIS_BUDGET" -> {
                String year = header.get("fiscalyear");
                List<String> status = year == null || !year.matches("\\d{4}") ? List.of()
                        : jdbc.queryForList("SELECT coalesce(b.status, 'NONE') FROM fiscal_years f LEFT JOIN budgets b ON b.fiscal_year_id = f.id "
                                + "WHERE f.fiscal_year = ?", String.class, Integer.parseInt(year));
                if (status.isEmpty()) {
                    errors.add("fiscalYear must name an existing fiscal year.");
                } else if (!Set.of("NONE", "DRAFT", "RETURNED").contains(status.get(0))) {
                    errors.add("The " + year + " budget is " + status.get(0).toLowerCase() + "; change it with budget adjustments instead.");
                }
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < rows.size(); i++) {
                    Map<String, String> r = rows.get(i);
                    String at = "Line " + (i + 1) + ": ";
                    checkAccount(r.get("accountcode"), at, errors, true);
                    if (r.get("accountcode") != null && !Boolean.TRUE.equals(jdbc.queryForList("SELECT budget_controlled FROM chart_of_accounts "
                            + "WHERE code = ?", Boolean.class, r.get("accountcode")).stream().findFirst().orElse(true))) {
                        errors.add(at + "account " + r.get("accountcode") + " is not under budget control.");
                    }
                    checkCostCenter(r.get("costcentercode"), at, errors);
                    decimal(r.get("amount"), at + "amount", errors, false);
                    if (!seen.add(r.get("accountcode") + "|" + r.getOrDefault("costcentercode", ""))) {
                        errors.add(at + "the account and cost centre appear twice.");
                    }
                }
            }
            default -> {
                checkAccount(header.get("offsetaccountcode"), "offsetAccountCode: ", errors, true);
                for (int i = 0; i < rows.size(); i++) {
                    Map<String, String> r = rows.get(i);
                    String at = "Line " + (i + 1) + ": ";
                    if (r.get("categorycode") == null || jdbc.queryForObject("SELECT count(*) FROM asset_categories WHERE code = ? AND status = 'ACTIVE'",
                            Long.class, r.get("categorycode")) == 0) {
                        errors.add(at + "categoryCode must name an active asset category.");
                    }
                    if (r.get("description") == null || r.get("description").isBlank()) {
                        errors.add(at + "description is required.");
                    }
                    checkCostCenter(r.get("costcentercode"), at, errors);
                    LocalDate acquired = date(r.get("acquisitiondate"), at + "acquisitionDate", errors);
                    LocalDate inService = date(r.get("inservicedate"), at + "inServiceDate", errors);
                    if (acquired != null && inService != null && inService.isBefore(acquired)) {
                        errors.add(at + "the in-service date is before the acquisition date.");
                    }
                    BigDecimal cost = decimal(r.get("cost"), at + "cost", errors, false);
                    if (cost != null && cost.signum() <= 0) {
                        errors.add(at + "cost must be more than zero.");
                    }
                    if (r.get("residualvalue") != null && !r.get("residualvalue").isBlank()) {
                        decimal(r.get("residualvalue"), at + "residualValue", errors, false);
                    }
                    String life = r.get("usefullifemonths");
                    if (life != null && !life.isBlank() && (!life.matches("\\d{1,4}") || Integer.parseInt(life) < 1 || Integer.parseInt(life) > 1200)) {
                        errors.add(at + "usefulLifeMonths must be a whole number from 1 to 1200.");
                    }
                }
            }
        }
    }

    private void checkAccount(String code, String at, List<String> errors, boolean postable) {
        if (code == null || code.isBlank()) {
            errors.add(at + "accountCode is required.");
            return;
        }
        List<Boolean> p = jdbc.queryForList("SELECT postable FROM chart_of_accounts WHERE code = ?", Boolean.class, code);
        if (p.isEmpty()) {
            errors.add(at + "account " + code + " does not exist.");
        } else if (postable && !Boolean.TRUE.equals(p.get(0))) {
            errors.add(at + "account " + code + " is a header account; use a postable account.");
        }
    }

    private void checkCostCenter(String code, String at, List<String> errors) {
        if (code != null && !code.isBlank() && jdbc.queryForObject("SELECT count(*) FROM cost_centers WHERE code = ?", Long.class, code) == 0) {
            errors.add(at + "cost centre " + code + " does not exist.");
        }
    }

    private static BigDecimal decimal(String v, String what, List<String> errors, boolean blankIsZero) {
        if (v == null || v.isBlank()) {
            if (blankIsZero) {
                return BigDecimal.ZERO;
            }
            errors.add(what + " is required.");
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(v.replace(",", ""));
            if (d.signum() < 0 || d.scale() > 2) {
                errors.add(what + " must be zero or more, with at most two decimals.");
                return null;
            }
            return d;
        } catch (NumberFormatException e) {
            errors.add(what + " is not a number: " + truncate(v, 30));
            return null;
        }
    }

    private static LocalDate date(String v, String what, List<String> errors) {
        try {
            return LocalDate.parse(v == null ? "" : v);
        } catch (DateTimeParseException e) {
            errors.add(what + " must be a date (YYYY-MM-DD).");
            return null;
        }
    }

    static LocalDate payrollDate(Map<String, String> header) {
        try {
            if (header.get("accountingdate") != null) {
                return LocalDate.parse(header.get("accountingdate"));
            }
            if (header.get("period") != null) {
                return YearMonth.parse(header.get("period")).atEndOfMonth();
            }
        } catch (DateTimeParseException e) {
            // reported below
        }
        throw new IllegalArgumentException("Give accountingDate (YYYY-MM-DD) or period (YYYY-MM).");
    }

    // ---------------------------------------------------------------- helpers

    private UUID account(String code) {
        return jdbc.queryForObject("SELECT id FROM chart_of_accounts WHERE code = ?", UUID.class, code);
    }

    private UUID costCenter(String code) {
        return code == null || code.isBlank() ? null : jdbc.queryForObject("SELECT id FROM cost_centers WHERE code = ?", UUID.class, code);
    }

    private UUID category(String code) {
        return jdbc.queryForObject("SELECT id FROM asset_categories WHERE code = ?", UUID.class, code);
    }

    private static BigDecimal amount(String v) {
        return v == null || v.isBlank() ? BigDecimal.ZERO : new BigDecimal(v.replace(",", ""));
    }

    static String permission(String type) {
        String p = PERMISSION.get(type);
        if (p == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown interface " + type + "; use PAYROLL, FMIS_BUDGET or ASSETS.");
        }
        return p;
    }

    private static boolean isApiClient() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> ApiClientAuthenticator.ROLE.equals(a.getAuthority()));
    }

    private Map<String, Object> lock(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT interface, status, content::text AS content, source_reference, version "
                + "FROM interface_batches WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Interface batch");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The batch was changed by someone else. Reload and try again.");
        }
        return rows.get(0);
    }

    private BatchView map(ResultSet rs, boolean detail) throws SQLException {
        Map<String, Object> content = detail ? read(rs.getString("content")) : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, String> header = (Map<String, String>) content.getOrDefault("header", Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) content.getOrDefault("rows", List.of());
        List<String> errors;
        try {
            errors = json.readValue(rs.getString("errors"), new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            errors = List.of();
        }
        java.sql.Array ids = rs.getArray("result_ids");
        List<UUID> results = ids == null ? List.of() : Arrays.stream((Object[]) ids.getArray()).map(o -> (UUID) o).toList();
        Timestamp decided = rs.getTimestamp("decided_at");
        return new BatchView(rs.getObject("id", UUID.class), rs.getString("interface"), rs.getString("format"), rs.getString("source_reference"),
                rs.getString("file_sha256"), rs.getString("status"), rs.getInt("row_count"), header, rows, errors, rs.getString("received_by"),
                rs.getTimestamp("received_at").toInstant(), rs.getString("decided_by"), decided == null ? null : decided.toInstant(),
                rs.getString("decision_note"), rs.getString("result_type"), results, rs.getLong("version"));
    }

    private Map<String, Object> read(String s) {
        try {
            return json.readValue(s, new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String truncate(String v, int n) {
        return v.length() > n ? v.substring(0, n) : v;
    }
}
