package com.miaa.cas.migration;

import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Items per customer or supplier: open invoices (AR, AP), unapplied collections (AR) and supplier advances (AP).
 * Each item's balance is on a control account that keeps the party's sub-ledger, so the batch must equal the
 * party's subsidiary balance on that account in the opening entry.
 */
class PartyItemRules implements KindRules {

    /** How one party kind differs from the others. */
    record Spec(MigrationService.Kind kind, boolean customers, String numberCol, int numberMax, String dateCol, boolean hasDue,
            String amountCol, int descriptionMax, String mappingKey, boolean debitNormal) {

        String partyCol() {
            return customers ? "customer_code" : "supplier_code";
        }

        String partyTable() {
            return customers ? "customers" : "suppliers";
        }

        String slType() {
            return customers ? "CUSTOMER" : "SUPPLIER";
        }
    }

    record Item(UUID partyId, String number, LocalDate date, LocalDate due, String description, BigDecimal amount) {
    }

    private record Party(UUID id, String code, String name, UUID subsidiaryId) {
    }

    private final Spec spec;
    private final AccountMappingService mappings;
    private final JdbcTemplate jdbc;

    PartyItemRules(Spec spec, AccountMappingService mappings, JdbcTemplate jdbc) {
        this.spec = spec;
        this.mappings = mappings;
        this.jdbc = jdbc;
    }

    @Override
    public MigrationService.Kind kind() {
        return spec.kind();
    }

    @Override
    public String basis() {
        return jdbc.queryForObject("SELECT code FROM chart_of_accounts WHERE id = ?", String.class, mappings.require(spec.mappingKey()));
    }

    @Override
    public Checked check(List<MigrationCsv.Row> rows, LocalDate cutover) {
        UUID control = mappings.require(spec.mappingKey());
        List<String> batchErrors = new ArrayList<>();
        Map<String, Object> acct = jdbc.queryForMap("SELECT code, subsidiary_type FROM chart_of_accounts WHERE id = ?", control);
        if (!spec.slType().equals(acct.get("subsidiary_type"))) {
            batchErrors.add("The account mapped to " + spec.mappingKey() + " (" + acct.get("code") + ") must keep a "
                    + spec.slType().toLowerCase(Locale.ROOT) + " sub-ledger so the items can be reconciled per party.");
        }
        Map<String, Party> parties = new HashMap<>();
        jdbc.query("SELECT id, code, name, subsidiary_account_id FROM " + spec.partyTable(), rs -> {
            parties.put(rs.getString("code"), new Party(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                    rs.getObject("subsidiary_account_id", UUID.class)));
        });
        Set<String> existing = existingNumbers(rows);
        List<List<String>> rowErrors = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        Map<String, Side> batch = KindRules.sides();
        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (MigrationCsv.Row r : rows) {
            List<String> e = new ArrayList<>();
            Map<String, String> v = r.values();
            Party party = parties.get(v.get(spec.partyCol()));
            if (v.get(spec.partyCol()).isEmpty()) {
                e.add(spec.partyCol() + " is required.");
            } else if (party == null) {
                e.add((spec.customers() ? "Customer " : "Supplier ") + v.get(spec.partyCol()) + " does not exist.");
            }
            String number = v.get(spec.numberCol());
            String key = scoped() ? (party == null ? "" : party.id()) + "|" + number.toLowerCase(Locale.ROOT) : number.toLowerCase(Locale.ROOT);
            if (number.isEmpty()) {
                e.add(spec.numberCol() + " is required.");
            } else if (number.length() > spec.numberMax()) {
                e.add(spec.numberCol() + " is longer than " + spec.numberMax() + " characters.");
            } else if (!seen.add(key)) {
                e.add(spec.numberCol() + " " + number + " appears more than once in the file.");
            } else if (existing.contains(key)) {
                e.add(spec.numberCol() + " " + number + " already exists in CAS.");
            }
            LocalDate date = KindRules.date(v.get(spec.dateCol()), spec.dateCol(), e);
            LocalDate due = spec.hasDue() ? KindRules.date(v.get("due_date"), "due_date", e) : null;
            if (date != null && date.isAfter(cutover)) {
                e.add(spec.dateCol() + " is after the cutover date " + cutover + ".");
            }
            if (date != null && due != null && due.isBefore(date)) {
                e.add("due_date is before " + spec.dateCol() + ".");
            }
            String description = KindRules.text(v.get("description"), "description", e, true, spec.descriptionMax());
            BigDecimal amount = KindRules.amount(v.get(spec.amountCol()), spec.amountCol(), e, false);
            if (amount != null && amount.signum() == 0) {
                e.add(spec.amountCol() + " must be greater than zero.");
            }
            if (e.isEmpty()) {
                total = total.add(amount);
                items.add(new Item(party.id(), number, date, due, description, amount));
                KindRules.add(batch, party.subsidiaryId().toString(), party.code(), party.name(), amount);
            }
            rowErrors.add(e);
        }
        return new Checked(rowErrors, batchErrors, total, batch, items, List.of());
    }

    /** Supplier invoices and advances are numbered per supplier; customer documents and receipts are numbered globally. */
    private boolean scoped() {
        return !spec.customers();
    }

    private Set<String> existingNumbers(List<MigrationCsv.Row> rows) {
        Object numbers = rows.stream().map(r -> r.values().get(spec.numberCol()).toLowerCase(Locale.ROOT)).distinct().toArray(String[]::new);
        Set<String> existing = new HashSet<>();
        switch (spec.kind()) {
            case AR_OPEN_ITEMS -> existing.addAll(jdbc.queryForList(
                    "SELECT lower(document_number) FROM billing_documents WHERE lower(document_number) = ANY(?)", String.class, numbers));
            case AR_UNAPPLIED -> existing.addAll(jdbc.queryForList(
                    "SELECT lower(receipt_number) FROM receipts WHERE lower(receipt_number) = ANY(?)", String.class, numbers));
            case AP_OPEN_ITEMS -> jdbc.query("SELECT supplier_id, lower(invoice_number) AS n FROM supplier_invoices "
                    + "WHERE status NOT IN ('REJECTED', 'CANCELLED') AND lower(invoice_number) = ANY(?)",
                    rs -> {
                        existing.add(rs.getObject("supplier_id", UUID.class) + "|" + rs.getString("n"));
                    }, numbers);
            case AP_ADVANCES -> jdbc.query("SELECT supplier_id, lower(reference) AS n FROM supplier_advances WHERE lower(reference) = ANY(?)",
                    rs -> {
                        existing.add(rs.getObject("supplier_id", UUID.class) + "|" + rs.getString("n"));
                    }, numbers);
            default -> throw new IllegalStateException(spec.kind().name());
        }
        return existing;
    }

    @Override
    public Map<String, Side> ledger(Entry entry) {
        UUID control = mappings.require(spec.mappingKey());
        Map<String, Side> sides = KindRules.sides();
        jdbc.query("""
                SELECT l.subsidiary_account_id AS sl, coalesce(p.code, s.code, '(no subsidiary)') AS code, coalesce(p.name, s.name, '') AS name,
                       sum(%s) AS amt
                FROM journal_entry_lines l LEFT JOIN subsidiary_ledger_accounts s ON s.id = l.subsidiary_account_id
                LEFT JOIN %s p ON p.subsidiary_account_id = s.id
                WHERE l.entry_id = ? AND l.account_id = ? GROUP BY 1, 2, 3""".formatted(
                        spec.debitNormal() ? "l.debit - l.credit" : "l.credit - l.debit", spec.partyTable()),
                rs -> {
                    KindRules.add(sides, String.valueOf(rs.getObject("sl")), rs.getString("code"), rs.getString("name"), rs.getBigDecimal("amt"));
                }, entry.id(), control);
        return sides;
    }

    @Override
    public void apply(Context ctx, Checked checked) {
        Entry entry = ctx.entry();
        List<Object[]> params = new ArrayList<>();
        for (Object o : checked.items()) {
            Item i = (Item) o;
            params.add(switch (spec.kind()) {
                case AR_OPEN_ITEMS -> new Object[] {UUID.randomUUID(), i.number(), i.partyId(), Date.valueOf(i.date()), Date.valueOf(i.due()),
                        entry.periodId(), i.description(), i.amount(), i.amount(), ctx.preparerId(), ctx.preparer(), entry.id(), ctx.now(), ctx.now(),
                        CurrentActor.username(), ctx.batchId()};
                case AP_OPEN_ITEMS -> new Object[] {UUID.randomUUID(), i.partyId(), i.number(), Date.valueOf(i.date()), Date.valueOf(i.due()),
                        Date.valueOf(entry.date()), entry.periodId(), i.description(), i.amount(), i.amount(), ctx.preparerId(), ctx.preparer(),
                        entry.id(), ctx.now(), ctx.now(), CurrentActor.username(), ctx.batchId()};
                case AR_UNAPPLIED -> new Object[] {UUID.randomUUID(), i.number(), i.partyId(), Date.valueOf(i.date()), i.amount(), i.amount(),
                        i.description(), ctx.preparerId(), ctx.preparer(), entry.id(), ctx.now(), ctx.now(), CurrentActor.username(), ctx.batchId()};
                case AP_ADVANCES -> new Object[] {UUID.randomUUID(), i.partyId(), i.number(), Date.valueOf(i.date()), i.description(), i.amount(),
                        i.amount(), entry.id(), ctx.batchId(), ctx.preparerId(), ctx.preparer(), ctx.now()};
                default -> throw new IllegalStateException(spec.kind().name());
            });
        }
        jdbc.batchUpdate(switch (spec.kind()) {
            case AR_OPEN_ITEMS -> """
                    INSERT INTO billing_documents (id, kind, document_number, customer_id, document_date, due_date, period_id, description,
                        status, net_amount, vat_amount, gross_amount, settled_amount, source, prepared_by_id, prepared_by, posted_entry_id,
                        created_at, updated_at, updated_by, migration_batch_id)
                    VALUES (?, 'INVOICE', ?, ?, ?, ?, ?, ?, 'POSTED', ?, 0, ?, 0, 'MIGRATION', ?, ?, ?, ?, ?, ?, ?)""";
            case AP_OPEN_ITEMS -> """
                    INSERT INTO supplier_invoices (id, supplier_id, invoice_number, invoice_date, due_date, accounting_date, period_id,
                        description, status, net_amount, payable_amount, prepared_by_id, prepared_by, posted_entry_id, created_at, updated_at,
                        updated_by, migration_batch_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
            // Collected and deposited in the legacy system: never offered for deposit, never in a daily collection report.
            case AR_UNAPPLIED -> """
                    INSERT INTO receipts (id, receipt_number, customer_id, receipt_date, payment_method, amount, tax_withheld, unapplied_amount,
                        status, remarks, recorded_by_id, recorded_by, posted_entry_id, created_at, updated_at, updated_by, migration_batch_id)
                    VALUES (?, ?, ?, ?, 'MIGRATED', ?, 0, ?, 'POSTED', ?, ?, ?, ?, ?, ?, ?, ?)""";
            case AP_ADVANCES -> """
                    INSERT INTO supplier_advances (id, supplier_id, reference, advance_date, description, amount, outstanding_amount, status,
                        posted_entry_id, migration_batch_id, prepared_by_id, prepared_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?)""";
            default -> throw new IllegalStateException(spec.kind().name());
        }, params);
    }
}
