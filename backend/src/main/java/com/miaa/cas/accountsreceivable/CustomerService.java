package com.miaa.cas.accountsreceivable;

import com.miaa.cas.accountsreceivable.ArDtos.ContractRequest;
import com.miaa.cas.accountsreceivable.ArDtos.ContractView;
import com.miaa.cas.accountsreceivable.ArDtos.CustomerRequest;
import com.miaa.cas.accountsreceivable.ArDtos.CustomerUpdateRequest;
import com.miaa.cas.accountsreceivable.ArDtos.CustomerView;
import com.miaa.cas.accountsreceivable.ArDtos.PortalUserView;
import com.miaa.cas.accountsreceivable.domain.Customer;
import com.miaa.cas.accountsreceivable.domain.CustomerRepository;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.generalledger.domain.SubsidiaryAccount;
import com.miaa.cas.generalledger.domain.SubsidiaryAccountRepository;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.tax.TaxCodeService;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer master file (concessionaires, airlines, government and other customers), their recurring
 * contracts, and the link from concessionaire portal accounts to the customer whose data they may see.
 * Each customer owns a CUSTOMER subsidiary-ledger account used on receivable postings.
 */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final SubsidiaryAccountRepository subsidiaries;
    private final TaxCodeService taxCodes;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, SubsidiaryAccountRepository subsidiaries, TaxCodeService taxCodes,
            AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.customers = customers;
        this.subsidiaries = subsidiaries;
        this.taxCodes = taxCodes;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ customers

    @Transactional(readOnly = true)
    public PageResponse<CustomerView> list(String q, int page, int size) {
        String like = JdbcPaging.like(q);
        return JdbcPaging.ids(jdbc, "SELECT id FROM customers WHERE (?::text IS NULL OR lower(code || ' ' || name || ' ' || coalesce(tin, '')) "
                + "LIKE ?::text) ORDER BY name, id", page, size, like, like).map(id -> view(customers.findById(id).orElseThrow()));
    }

    @Transactional(readOnly = true)
    public CustomerView get(UUID id) {
        return view(load(id));
    }

    @Transactional
    public CustomerView create(CustomerRequest req) {
        if (customers.existsByCode(req.code()) || subsidiaries.existsBySlTypeAndCode("CUSTOMER", req.code())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Customer code already exists.");
        }
        Instant now = Instant.now(clock);
        SubsidiaryAccount sl = subsidiaries.saveAndFlush(new SubsidiaryAccount(UUID.randomUUID(), "CUSTOMER", req.code(),
                req.name().trim(), now, CurrentActor.username()));
        Customer c = new Customer(UUID.randomUUID(), req.code(), sl.getId(), now, CurrentActor.username());
        c.update(req.name().trim(), req.customerType(), blank(req.tin()), blank(req.address()), blank(req.email()),
                money(req.creditLimit()), req.paymentTermsDays(), "ACTIVE", now, CurrentActor.username());
        customers.saveAndFlush(c);
        audit.record("CUSTOMER_CREATED", "CUSTOMER", c.getCode(), Map.of("name", c.getName(), "type", c.getCustomerType()));
        return view(c);
    }

    @Transactional
    public CustomerView update(UUID id, CustomerUpdateRequest req) {
        Customer c = load(id);
        if (c.getVersion() != req.version()) {
            throw ApiException.staleVersion();
        }
        Map<String, Object> before = snapshot(c);
        c.update(req.name().trim(), req.customerType(), blank(req.tin()), blank(req.address()), blank(req.email()),
                money(req.creditLimit()), req.paymentTermsDays(), req.status(), Instant.now(clock), CurrentActor.username());
        subsidiaries.findById(c.getSubsidiaryAccountId()).ifPresent(sl -> sl.update(c.getName(), c.getStatus()));
        customers.flush();
        audit.record("CUSTOMER_UPDATED", "CUSTOMER", c.getCode(), Map.of("before", before, "after", snapshot(c)));
        return view(c);
    }

    Customer load(UUID id) {
        return customers.findById(id).orElseThrow(() -> ApiException.notFound("Customer"));
    }

    Customer active(UUID id) {
        Customer c = load(id);
        if (!c.isActive()) {
            throw BillingService.rule("Customer " + c.getCode() + " is inactive.");
        }
        return c;
    }

    /** Posted invoices and debit memos not yet settled by collections or credit memos. */
    BigDecimal openBalance(UUID customerId) {
        return Money.of(jdbc.queryForObject("SELECT coalesce(sum(gross_amount - settled_amount), 0) FROM billing_documents "
                + "WHERE customer_id = ? AND status = 'POSTED' AND kind <> 'CREDIT_MEMO'", BigDecimal.class, customerId));
    }

    BigDecimal unapplied(UUID customerId) {
        return Money.of(jdbc.queryForObject("SELECT coalesce(sum(unapplied_amount), 0) FROM receipts WHERE customer_id = ? "
                + "AND status = 'POSTED'", BigDecimal.class, customerId));
    }

    private CustomerView view(Customer c) {
        return new CustomerView(c.getId(), c.getCode(), c.getName(), c.getCustomerType(), c.getTin(), c.getAddress(),
                c.getEmail(), c.getCreditLimit(), c.getPaymentTermsDays(), c.getSubsidiaryAccountId(), c.getStatus(),
                openBalance(c.getId()), unapplied(c.getId()), c.getVersion());
    }

    private static Map<String, Object> snapshot(Customer c) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", c.getName());
        m.put("type", c.getCustomerType());
        m.put("tin", String.valueOf(c.getTin()));
        m.put("creditLimit", c.getCreditLimit() == null ? "none" : c.getCreditLimit().toPlainString());
        m.put("paymentTermsDays", c.getPaymentTermsDays());
        m.put("status", c.getStatus());
        return m;
    }

    // ------------------------------------------------------------------ contracts

    @Transactional(readOnly = true)
    public List<ContractView> contracts(UUID customerId) {
        load(customerId);
        return jdbc.query(CONTRACT_SQL + " WHERE k.customer_id = ? ORDER BY k.start_date DESC, k.contract_number",
                (rs, i) -> contract(rs), customerId);
    }

    @Transactional
    public ContractView createContract(UUID customerId, ContractRequest req) {
        Customer c = active(customerId);
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The end date cannot precede the start date.");
        }
        requireRevenueAccount(req.revenueAccountId());
        if (req.vatCodeId() != null) {
            taxCodes.requireActive(req.vatCodeId(), "VAT_OUTPUT");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO customer_contracts (id, customer_id, contract_number, description, start_date, end_date,
                        monthly_amount, revenue_account_id, cost_center_id, vat_code_id, status, created_at, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)""", id, c.getId(), req.contractNumber().trim(),
                    req.description().trim(), Date.valueOf(req.startDate()),
                    req.endDate() == null ? null : Date.valueOf(req.endDate()), Money.of(req.monthlyAmount()),
                    req.revenueAccountId(), req.costCenterId(), req.vatCodeId(), Timestamp.from(Instant.now(clock)),
                    CurrentActor.username());
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Contract number already exists.");
        }
        audit.record("CONTRACT_CREATED", "CUSTOMER_CONTRACT", id, Map.of("customer", c.getCode(),
                "contractNumber", req.contractNumber().trim(), "monthlyAmount", Money.of(req.monthlyAmount()).toPlainString()));
        return contract(id);
    }

    @Transactional
    public ContractView setContractStatus(UUID contractId, long version, String status) {
        ContractView k = contract(contractId);
        int n = jdbc.update("UPDATE customer_contracts SET status = ?, version = version + 1 WHERE id = ? AND version = ?",
                status, contractId, version);
        if (n == 0) {
            throw ApiException.staleVersion();
        }
        audit.record("CONTRACT_STATUS_CHANGED", "CUSTOMER_CONTRACT", contractId, Map.of("from", k.status(), "to", status));
        return contract(contractId);
    }

    ContractView contract(UUID id) {
        List<ContractView> rows = jdbc.query(CONTRACT_SQL + " WHERE k.id = ?", (rs, i) -> contract(rs), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Contract");
        }
        return rows.get(0);
    }

    /** Active contracts that cover at least one day of the given month. */
    List<ContractView> billableContracts(java.time.LocalDate first, java.time.LocalDate last) {
        return jdbc.query(CONTRACT_SQL + " WHERE k.status = 'ACTIVE' AND k.start_date <= ? AND (k.end_date IS NULL OR "
                + "k.end_date >= ?) ORDER BY k.contract_number", (rs, i) -> contract(rs), Date.valueOf(last), Date.valueOf(first));
    }

    private static final String CONTRACT_SQL = """
            SELECT k.*, a.code AS account_code, cc.code AS cc_code, t.code AS vat_code FROM customer_contracts k
            JOIN chart_of_accounts a ON a.id = k.revenue_account_id LEFT JOIN cost_centers cc ON cc.id = k.cost_center_id
            LEFT JOIN tax_codes t ON t.id = k.vat_code_id""";

    private static ContractView contract(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date end = rs.getDate("end_date");
        return new ContractView(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getString("contract_number"), rs.getString("description"), rs.getDate("start_date").toLocalDate(),
                end == null ? null : end.toLocalDate(), rs.getBigDecimal("monthly_amount"),
                rs.getObject("revenue_account_id", UUID.class), rs.getString("account_code"),
                rs.getObject("cost_center_id", UUID.class), rs.getString("cc_code"), rs.getObject("vat_code_id", UUID.class),
                rs.getString("vat_code"), rs.getString("status"), rs.getLong("version"));
    }

    void requireRevenueAccount(UUID accountId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT code, account_type, postable, status FROM chart_of_accounts "
                + "WHERE id = ?", accountId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Account");
        }
        Map<String, Object> a = rows.get(0);
        if (!"REVENUE".equals(a.get("account_type")) || !Boolean.TRUE.equals(a.get("postable")) || !"ACTIVE".equals(a.get("status"))) {
            throw BillingService.rule("Account " + a.get("code") + " is not an active, postable revenue account.");
        }
    }

    // ------------------------------------------------------------------ portal users

    @Transactional(readOnly = true)
    public List<PortalUserView> portalUsers(UUID customerId) {
        load(customerId);
        return jdbc.query("SELECT id, username, display_name, status FROM users WHERE customer_id = ? ORDER BY username",
                (rs, i) -> new PortalUserView(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("status")), customerId);
    }

    @Transactional
    public List<PortalUserView> unlinkPortalUser(UUID customerId, UUID userId) {
        Customer c = load(customerId);
        List<String> names = jdbc.queryForList("SELECT username FROM users WHERE id = ? AND customer_id = ?", String.class,
                userId, customerId);
        if (names.isEmpty()) {
            throw ApiException.notFound("Linked portal user");
        }
        jdbc.update("UPDATE users SET customer_id = NULL WHERE id = ?", userId);
        audit.record("PORTAL_USER_UNLINKED", "CUSTOMER", c.getCode(), Map.of("username", names.get(0)));
        return portalUsers(customerId);
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : Money.of(v);
    }
}
