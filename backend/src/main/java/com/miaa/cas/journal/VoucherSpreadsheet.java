package com.miaa.cas.journal;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.journal.web.JournalDtos.LineRequest;
import com.miaa.cas.journal.web.JournalDtos.VoucherRequest;
import com.miaa.cas.security.CurrentActor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Excel (.xlsx) exchange of journal vouchers. The export lists every voucher line in a date range; the import reads
 * the same columns and creates draft vouchers, which then go through the normal review, approval and posting. An
 * exported file can be re-imported as new drafts (its numbers become references only).
 */
@Service
public class VoucherSpreadsheet {

    /** Import and export columns, in order; the import finds them by header text, case-insensitively. */
    static final List<String> COLUMNS = List.of("Voucher reference", "Voucher type", "Accounting date", "Voucher description",
            "Supporting reference", "Account code", "Cost centre code", "Subsidiary account code", "Line description", "Debit", "Credit");
    /** Export-only columns after {@link #COLUMNS}; the import ignores them. */
    static final List<String> INFO_COLUMNS = List.of("Status", "Account title", "Cost centre", "Subsidiary account", "Prepared by");
    static final int MAX_EXPORT_LINES = 50_000;
    static final int MAX_IMPORT_ROWS = 5_000;
    static final int MAX_IMPORT_VOUCHERS = 200;
    private static final Set<String> IMPORT_TYPES = Set.of("STANDARD", "ADJUSTING", "OPENING");

    private final JdbcTemplate jdbc;
    private final JournalVoucherService commands;
    private final AuditService audit;

    public VoucherSpreadsheet(JdbcTemplate jdbc, JournalVoucherService commands, AuditService audit) {
        this.jdbc = jdbc;
        this.commands = commands;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ export

    /** Every line of the vouchers dated {@code from}..{@code to}, filtered like the voucher list. Audited. */
    @Transactional
    public byte[] export(LocalDate from, LocalDate to, String status, String type, String q, boolean mine) {
        if (from == null || to == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose the From and To dates to export.");
        }
        if (to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The To date cannot be earlier than the From date.");
        }
        StringBuilder where = new StringBuilder(" WHERE v.accounting_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(from, to));
        if (status != null && !status.isBlank()) {
            where.append(" AND v.status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND v.voucher_type = ?");
            args.add(type.trim().toUpperCase(Locale.ROOT));
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (lower(v.description) LIKE ? OR lower(v.voucher_number) LIKE ?)");
            args.add(pattern);
            args.add(pattern);
        }
        if (mine) {
            where.append(" AND v.prepared_by_id = ?");
            args.add(CurrentActor.userId());
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM journal_voucher_lines l JOIN journal_vouchers v ON v.id = l.voucher_id"
                + where, Integer.class, args.toArray());
        if (count != null && count > MAX_EXPORT_LINES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The range has " + count + " voucher lines; one file holds at most "
                    + MAX_EXPORT_LINES + ". Choose a shorter date range.");
        }
        List<Object[]> rows = jdbc.query("""
                SELECT coalesce(v.voucher_number, 'DRAFT-' || left(v.id::text, 8)), v.voucher_type, v.accounting_date, v.description,
                       v.supporting_reference, a.code, cc.code, sl.code, l.description, l.debit, l.credit,
                       v.status, a.name, cc.name, sl.name, v.prepared_by
                FROM journal_voucher_lines l
                JOIN journal_vouchers v ON v.id = l.voucher_id
                JOIN chart_of_accounts a ON a.id = l.account_id
                LEFT JOIN cost_centers cc ON cc.id = l.cost_center_id
                LEFT JOIN subsidiary_ledger_accounts sl ON sl.id = l.subsidiary_account_id""" + where
                + " ORDER BY v.accounting_date, v.voucher_number NULLS LAST, v.id, l.line_no", (rs, i) -> {
                    Object[] r = new Object[COLUMNS.size() + INFO_COLUMNS.size()];
                    for (int c = 0; c < r.length; c++) {
                        r[c] = rs.getObject(c + 1);
                    }
                    r[2] = rs.getDate(3).toLocalDate();
                    return r;
                }, args.toArray());
        byte[] file = workbook(rows, from, to);
        audit.record("JOURNAL_VOUCHERS_EXPORTED", "JOURNAL_VOUCHER", from + ".." + to, Map.of("lines", rows.size(),
                "status", status == null ? "" : status, "format", "XLSX"));
        return file;
    }

    /** The import template: the header row, one example voucher and a sheet describing each column. */
    public byte[] template() {
        List<Object[]> example = List.of(
                new Object[] {"JV-1", "STANDARD", LocalDate.now(), "Example: accrual of utilities", "SOA-0001", "<expense account code>",
                    "<cost centre code>", null, "Electricity", new BigDecimal("1000.00"), null},
                new Object[] {"JV-1", "STANDARD", LocalDate.now(), "Example: accrual of utilities", "SOA-0001", "<liability account code>",
                    null, "<supplier code, if the account keeps a sub-ledger>", "Electricity", null, new BigDecimal("1000.00")});
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles s = new Styles(wb);
            Sheet lines = wb.createSheet("Lines");
            header(lines, COLUMNS, s);
            int r = 1;
            for (Object[] row : example) {
                write(lines.createRow(r++), row, COLUMNS.size(), s);
            }
            widths(lines, COLUMNS.size());
            Sheet help = wb.createSheet("Instructions");
            String[][] notes = {
                {"Voucher reference", "Groups rows into one voucher. Any text; rows with the same reference form one voucher."},
                {"Voucher type", "STANDARD, ADJUSTING or OPENING. Blank means STANDARD."},
                {"Accounting date", "A date cell or YYYY-MM-DD. Must fall in an open period. Same on every row of a voucher."},
                {"Voucher description", "Required. Same on every row of a voucher."},
                {"Supporting reference", "Optional (invoice, SOA or other source document number)."},
                {"Account code", "Required. An active, postable account from the chart of accounts."},
                {"Cost centre code", "Required when the account needs a cost centre."},
                {"Subsidiary account code", "Supplier, customer or employee code, when the account keeps a sub-ledger."},
                {"Line description", "Optional."},
                {"Debit / Credit", "One of them per row, up to 2 decimals."},
                {"", "Imported vouchers are saved as drafts prepared by you. Nothing is posted until they are reviewed, "
                    + "approved and posted as usual. If any row has a problem, nothing is imported and every problem is listed."},
                {"", "At most " + MAX_IMPORT_VOUCHERS + " vouchers and " + MAX_IMPORT_ROWS + " rows per file."}};
            int h = 0;
            for (String[] n : notes) {
                Row row = help.createRow(h++);
                row.createCell(0).setCellValue(n[0]);
                row.getCell(0).setCellStyle(s.bold);
                row.createCell(1).setCellValue(n[1]);
            }
            help.setColumnWidth(0, 26 * 256);
            help.setColumnWidth(1, 110 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] workbook(List<Object[]> rows, LocalDate from, LocalDate to) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles s = new Styles(wb);
            Sheet lines = wb.createSheet("Lines");
            List<String> all = new ArrayList<>(COLUMNS);
            all.addAll(INFO_COLUMNS);
            header(lines, all, s);
            int r = 1;
            BigDecimal debit = BigDecimal.ZERO;
            BigDecimal credit = BigDecimal.ZERO;
            for (Object[] row : rows) {
                write(lines.createRow(r++), row, all.size(), s);
                debit = debit.add((BigDecimal) row[9]);
                credit = credit.add((BigDecimal) row[10]);
            }
            widths(lines, all.size());
            lines.createFreezePane(0, 1);
            Sheet about = wb.createSheet("About");
            String[][] info = {{"Journal vouchers", from + " to " + to}, {"Lines", String.valueOf(rows.size())},
                {"Total debit", debit.toPlainString()}, {"Total credit", credit.toPlainString()},
                {"Exported by", CurrentActor.username()},
                {"Note", "The Lines sheet uses the import columns, so this file can be imported again as new draft vouchers."}};
            for (int i = 0; i < info.length; i++) {
                Row row = about.createRow(i);
                row.createCell(0).setCellValue(info[i][0]);
                row.getCell(0).setCellStyle(s.bold);
                row.createCell(1).setCellValue(info[i][1]);
            }
            about.setColumnWidth(0, 18 * 256);
            about.setColumnWidth(1, 90 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Styles {
        final CellStyle bold;
        final CellStyle date;
        final CellStyle amount;

        Styles(XSSFWorkbook wb) {
            XSSFFont font = wb.createFont();
            font.setBold(true);
            bold = wb.createCellStyle();
            bold.setFont(font);
            date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
            amount = wb.createCellStyle();
            amount.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        }
    }

    private static void header(Sheet sheet, List<String> names, Styles s) {
        Row head = sheet.createRow(0);
        for (int c = 0; c < names.size(); c++) {
            head.createCell(c).setCellValue(names.get(c));
            head.getCell(c).setCellStyle(s.bold);
        }
    }

    private static void write(Row row, Object[] values, int width, Styles s) {
        for (int c = 0; c < width && c < values.length; c++) {
            Object v = values[c];
            if (v == null) {
                continue;
            }
            if (v instanceof BigDecimal b && b.signum() == 0) {
                continue;
            }
            Cell cell = row.createCell(c);
            if (v instanceof LocalDate d) {
                cell.setCellValue(d);
                cell.setCellStyle(s.date);
            } else if (v instanceof BigDecimal b) {
                cell.setCellValue(b.doubleValue());
                cell.setCellStyle(s.amount);
            } else {
                // Always a string cell, never a formula, whatever the text starts with.
                cell.setCellValue(v.toString());
            }
        }
    }

    private static void widths(Sheet sheet, int columns) {
        for (int c = 0; c < columns; c++) {
            sheet.setColumnWidth(c, (c == 3 || c == 8 || c == 12 ? 40 : 18) * 256);
        }
    }

    // ------------------------------------------------------------------ import

    public record ImportResult(int vouchers, int lines, List<UUID> voucherIds) {
    }

    /** One spreadsheet row, already resolved to ids. */
    private record Parsed(int row, String reference, String type, LocalDate date, String description, String supportingReference,
            LineRequest line) {
    }

    /**
     * Creates one draft voucher per reference. Every row is checked first; if any has a problem nothing is created and
     * every problem is reported with its row number. Each voucher is then created through the normal voucher rules, and
     * a failure there also undoes the whole import.
     */
    @Transactional
    public ImportResult importDrafts(byte[] file) {
        List<String> problems = new ArrayList<>();
        Map<String, List<Parsed>> groups = new LinkedHashMap<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = wb.getSheet("Lines") != null ? wb.getSheet("Lines") : wb.getSheetAt(0);
            Row head = sheet.getRow(sheet.getFirstRowNum());
            Map<String, Integer> col = columns(head);
            for (String required : COLUMNS) {
                if (!col.containsKey(required.toLowerCase(Locale.ROOT))) {
                    problems.add("Missing column \"" + required + "\" in the header row. Download the template for the expected columns.");
                }
            }
            if (!problems.isEmpty()) {
                throw rejected(problems);
            }
            Lookups lookups = new Lookups();
            int rows = 0;
            for (int i = head.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || blank(row, col)) {
                    continue;
                }
                if (++rows > MAX_IMPORT_ROWS) {
                    problems.add("The file has more than " + MAX_IMPORT_ROWS + " rows; split it into smaller files.");
                    break;
                }
                Parsed p = parse(row, col, lookups, problems);
                if (p != null) {
                    groups.computeIfAbsent(p.reference(), k -> new ArrayList<>()).add(p);
                }
            }
            if (rows == 0) {
                problems.add("The file has no voucher rows below the header.");
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "The file could not be read as an Excel workbook (.xlsx).");
        }
        if (groups.size() > MAX_IMPORT_VOUCHERS) {
            problems.add("The file has " + groups.size() + " vouchers; import at most " + MAX_IMPORT_VOUCHERS + " at a time.");
        }
        List<VoucherRequest> requests = new ArrayList<>();
        for (Map.Entry<String, List<Parsed>> g : groups.entrySet()) {
            Parsed first = g.getValue().get(0);
            for (Parsed p : g.getValue()) {
                if (!p.type().equals(first.type()) || !p.date().equals(first.date()) || !p.description().equals(first.description())
                        || !java.util.Objects.equals(p.supportingReference(), first.supportingReference())) {
                    problems.add("Row " + p.row() + ": voucher " + g.getKey() + " must have the same type, date, description and "
                            + "supporting reference on every row (as on row " + first.row() + ").");
                }
            }
            BigDecimal dr = g.getValue().stream().map(p -> p.line().debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cr = g.getValue().stream().map(p -> p.line().credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (dr.compareTo(cr) != 0) {
                problems.add("Voucher " + g.getKey() + " (row " + first.row() + "): debits " + dr.toPlainString() + " and credits "
                        + cr.toPlainString() + " do not balance.");
            }
            if (g.getValue().size() > 500) {
                problems.add("Voucher " + g.getKey() + " has more than 500 lines.");
            }
            requests.add(new VoucherRequest(first.type(), first.date(), false, first.description(), first.supportingReference(),
                    g.getValue().stream().map(Parsed::line).toList()));
        }
        if (!problems.isEmpty()) {
            throw rejected(problems);
        }
        List<UUID> ids = new ArrayList<>();
        List<String> references = new ArrayList<>(groups.keySet());
        for (int i = 0; i < requests.size(); i++) {
            try {
                ids.add(commands.create(requests.get(i)).id());
            } catch (ApiException e) {
                // The transaction is rolled back, so no voucher from this file is kept.
                throw new ApiException(e.code(), "Voucher " + references.get(i) + " (row " + groups.get(references.get(i)).get(0).row()
                        + "): " + e.getMessage() + " Nothing was imported.", e.properties());
            }
        }
        int lineCount = requests.stream().mapToInt(r -> r.lines().size()).sum();
        audit.record("JOURNAL_VOUCHERS_IMPORTED", "JOURNAL_VOUCHER", "import", Map.of("vouchers", requests.size(), "lines", lineCount));
        return new ImportResult(requests.size(), lineCount, ids);
    }

    private static ApiException rejected(List<String> problems) {
        List<String> shown = problems.stream().limit(100).toList();
        String more = problems.size() > shown.size() ? " (first " + shown.size() + " shown)" : "";
        return new ApiException(ErrorCode.VALIDATION_FAILED, "The file has " + problems.size() + " problem"
                + (problems.size() == 1 ? "" : "s") + more + "; nothing was imported.", Map.of("violations", shown));
    }

    private static Map<String, Integer> columns(Row head) {
        Map<String, Integer> col = new HashMap<>();
        if (head != null) {
            for (Cell c : head) {
                String name = new DataFormatter().formatCellValue(c).trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    col.putIfAbsent(name, c.getColumnIndex());
                }
            }
        }
        return col;
    }

    private static boolean blank(Row row, Map<String, Integer> col) {
        for (String name : COLUMNS) {
            if (text(row, col, name) != null) {
                return false;
            }
        }
        return true;
    }

    private Parsed parse(Row row, Map<String, Integer> col, Lookups lookups, List<String> problems) {
        int n = row.getRowNum() + 1;
        int before = problems.size();
        String reference = text(row, col, "Voucher reference");
        if (reference == null) {
            problems.add("Row " + n + ": Voucher reference is required.");
        }
        String type = text(row, col, "Voucher type");
        type = type == null ? "STANDARD" : type.toUpperCase(Locale.ROOT);
        if (!IMPORT_TYPES.contains(type)) {
            problems.add("Row " + n + ": Voucher type must be STANDARD, ADJUSTING or OPENING (not " + type + ").");
        }
        LocalDate date = date(row, col, "Accounting date", n, problems);
        String description = text(row, col, "Voucher description");
        if (description == null) {
            problems.add("Row " + n + ": Voucher description is required.");
        } else if (description.length() > 1000) {
            problems.add("Row " + n + ": Voucher description is longer than 1000 characters.");
        }
        String supporting = text(row, col, "Supporting reference");
        if (supporting != null && supporting.length() > 200) {
            problems.add("Row " + n + ": Supporting reference is longer than 200 characters.");
        }
        String lineDescription = text(row, col, "Line description");
        if (lineDescription != null && lineDescription.length() > 500) {
            problems.add("Row " + n + ": Line description is longer than 500 characters.");
        }
        BigDecimal debit = amount(row, col, "Debit", n, problems);
        BigDecimal credit = amount(row, col, "Credit", n, problems);
        if (debit != null && credit != null && (debit.signum() > 0) == (credit.signum() > 0)) {
            problems.add("Row " + n + ": enter an amount in either Debit or Credit, not " + (debit.signum() > 0 ? "both." : "neither."));
        }
        UUID account = null;
        String slType = null;
        String accountCode = text(row, col, "Account code");
        if (accountCode == null) {
            problems.add("Row " + n + ": Account code is required.");
        } else {
            Map<String, Object> a = lookups.account(accountCode);
            if (a == null) {
                problems.add("Row " + n + ": no account has the code " + accountCode + ".");
            } else {
                account = (UUID) a.get("id");
                slType = (String) a.get("subsidiary_type");
            }
        }
        UUID costCenter = null;
        String ccCode = text(row, col, "Cost centre code");
        if (ccCode != null) {
            costCenter = lookups.costCenter(ccCode);
            if (costCenter == null) {
                problems.add("Row " + n + ": no active cost centre has the code " + ccCode + ".");
            }
        }
        UUID subsidiary = null;
        String slCode = text(row, col, "Subsidiary account code");
        if (slCode != null && account != null) {
            if (slType == null) {
                problems.add("Row " + n + ": account " + accountCode + " keeps no sub-ledger; leave Subsidiary account code blank.");
            } else {
                subsidiary = lookups.subsidiary(slType, slCode);
                if (subsidiary == null) {
                    problems.add("Row " + n + ": no active " + slType.toLowerCase(Locale.ROOT) + " sub-ledger account has the code " + slCode + ".");
                }
            }
        }
        if (problems.size() > before) {
            return null;
        }
        return new Parsed(n, reference, type, date, description, supporting,
                new LineRequest(account, costCenter, subsidiary, lineDescription, debit, credit));
    }

    private static String text(Row row, Map<String, Integer> col, String name) {
        Integer i = col.get(name.toLowerCase(Locale.ROOT));
        Cell c = i == null ? null : row.getCell(i);
        if (c == null) {
            return null;
        }
        String v = new DataFormatter().formatCellValue(c).trim();
        if (c.getCellType() == CellType.FORMULA) {
            v = c.getCachedFormulaResultType() == CellType.NUMERIC
                    ? new DataFormatter().formatRawCellContents(c.getNumericCellValue(), c.getCellStyle().getDataFormat(),
                            c.getCellStyle().getDataFormatString()).trim()
                    : c.getCachedFormulaResultType() == CellType.STRING ? c.getStringCellValue().trim() : "";
        }
        return v.isEmpty() ? null : v;
    }

    private static LocalDate date(Row row, Map<String, Integer> col, String name, int n, List<String> problems) {
        Integer i = col.get(name.toLowerCase(Locale.ROOT));
        Cell c = i == null ? null : row.getCell(i);
        if (c != null && numeric(c) && DateUtil.isCellDateFormatted(c)) {
            return c.getLocalDateTimeCellValue().toLocalDate();
        }
        String v = text(row, col, name);
        if (v == null) {
            problems.add("Row " + n + ": " + name + " is required.");
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            problems.add("Row " + n + ": " + name + " \"" + v + "\" is not a date; use a date cell or YYYY-MM-DD.");
            return null;
        }
    }

    private static boolean numeric(Cell c) {
        return c.getCellType() == CellType.NUMERIC
                || c.getCellType() == CellType.FORMULA && c.getCachedFormulaResultType() == CellType.NUMERIC;
    }

    private static BigDecimal amount(Row row, Map<String, Integer> col, String name, int n, List<String> problems) {
        Integer i = col.get(name.toLowerCase(Locale.ROOT));
        Cell c = i == null ? null : row.getCell(i);
        BigDecimal value;
        if (c != null && numeric(c)) {
            // The number as Excel shows it (15 significant digits), so spreadsheet arithmetic noise (0.1 + 0.2) disappears.
            value = new BigDecimal(NumberToTextConverter.toText(c.getNumericCellValue()));
        } else {
            String v = text(row, col, name);
            if (v == null) {
                return BigDecimal.ZERO;
            }
            try {
                value = new BigDecimal(v.replace(",", "").replace("₱", "").trim());
            } catch (NumberFormatException e) {
                problems.add("Row " + n + ": " + name + " \"" + v + "\" is not an amount.");
                return null;
            }
        }
        if (value.signum() < 0) {
            problems.add("Row " + n + ": " + name + " cannot be negative.");
            return null;
        }
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        if (value.stripTrailingZeros().scale() > 2) {
            problems.add("Row " + n + ": " + name + " has more than 2 decimals.");
            return null;
        }
        if (rounded.precision() - rounded.scale() > 17) {
            problems.add("Row " + n + ": " + name + " is too large.");
            return null;
        }
        return rounded;
    }

    /** Code lookups cached for one import. */
    private final class Lookups {
        private final Map<String, Map<String, Object>> accounts = new HashMap<>();
        private final Map<String, UUID> costCenters = new HashMap<>();
        private final Map<String, UUID> subsidiaries = new HashMap<>();

        Map<String, Object> account(String code) {
            return accounts.computeIfAbsent(code, k -> jdbc.queryForList(
                    "SELECT id, subsidiary_type FROM chart_of_accounts WHERE code = ?", k).stream().findFirst().orElse(null));
        }

        UUID costCenter(String code) {
            return costCenters.computeIfAbsent(code, k -> jdbc.queryForList(
                    "SELECT id FROM cost_centers WHERE code = ? AND status = 'ACTIVE'", UUID.class, k).stream().findFirst().orElse(null));
        }

        UUID subsidiary(String type, String code) {
            return subsidiaries.computeIfAbsent(type + "|" + code, k -> jdbc.queryForList(
                    "SELECT id FROM subsidiary_ledger_accounts WHERE sl_type = ? AND code = ? AND status = 'ACTIVE'", UUID.class, type, code)
                    .stream().findFirst().orElse(null));
        }
    }
}
