package com.miaa.cas.accountspayable;

import com.miaa.cas.accountspayable.ApDtos.SupplierRequest;
import com.miaa.cas.accountspayable.ApDtos.SupplierUpdateRequest;
import com.miaa.cas.accountspayable.ApDtos.SupplierView;
import com.miaa.cas.accountspayable.domain.Supplier;
import com.miaa.cas.accountspayable.domain.SupplierRepository;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.generalledger.domain.SubsidiaryAccount;
import com.miaa.cas.generalledger.domain.SubsidiaryAccountRepository;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.tax.TaxCodeService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Supplier master file. Each supplier owns a SUPPLIER subsidiary-ledger account used on AP postings. */
@Service
public class SupplierService {

    private final SupplierRepository suppliers;
    private final SubsidiaryAccountRepository subsidiaries;
    private final TaxCodeService taxCodes;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SupplierService(SupplierRepository suppliers, SubsidiaryAccountRepository subsidiaries, TaxCodeService taxCodes,
            AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.suppliers = suppliers;
        this.subsidiaries = subsidiaries;
        this.taxCodes = taxCodes;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierView> list(String q, int page, int size) {
        String like = JdbcPaging.like(q);
        return JdbcPaging.ids(jdbc, "SELECT id FROM suppliers WHERE (?::text IS NULL OR lower(code || ' ' || name || ' ' || coalesce(tin, '')) "
                + "LIKE ?::text) ORDER BY name, id", page, size, like, like).map(id -> view(suppliers.findById(id).orElseThrow()));
    }

    @Transactional(readOnly = true)
    public SupplierView get(UUID id) {
        return view(load(id));
    }

    @Transactional
    public SupplierView create(SupplierRequest req) {
        if (suppliers.existsByCode(req.code()) || subsidiaries.existsBySlTypeAndCode("SUPPLIER", req.code())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Supplier code already exists.");
        }
        checkEwt(req.defaultEwtCodeId());
        Instant now = Instant.now(clock);
        SubsidiaryAccount sl = subsidiaries.saveAndFlush(new SubsidiaryAccount(UUID.randomUUID(), "SUPPLIER", req.code(),
                req.name().trim(), now, CurrentActor.username()));
        Supplier s = new Supplier(UUID.randomUUID(), req.code(), sl.getId(), now, CurrentActor.username());
        s.update(req.name().trim(), blank(req.tin()), blank(req.address()), req.vatRegistered(), req.defaultEwtCodeId(),
                "ACTIVE", now, CurrentActor.username());
        suppliers.saveAndFlush(s);
        audit.record("SUPPLIER_CREATED", "SUPPLIER", s.getCode(), Map.of("name", s.getName(), "vatRegistered", s.isVatRegistered()));
        return view(s);
    }

    @Transactional
    public SupplierView update(UUID id, SupplierUpdateRequest req) {
        Supplier s = load(id);
        if (s.getVersion() != req.version()) {
            throw ApiException.staleVersion();
        }
        checkEwt(req.defaultEwtCodeId());
        Map<String, Object> before = Map.of("name", s.getName(), "tin", String.valueOf(s.getTin()), "status", s.getStatus(),
                "vatRegistered", s.isVatRegistered());
        s.update(req.name().trim(), blank(req.tin()), blank(req.address()), req.vatRegistered(), req.defaultEwtCodeId(),
                req.status(), Instant.now(clock), CurrentActor.username());
        subsidiaries.findById(s.getSubsidiaryAccountId()).ifPresent(sl -> sl.update(s.getName(), s.getStatus()));
        suppliers.flush();
        audit.record("SUPPLIER_UPDATED", "SUPPLIER", s.getCode(), Map.of("before", before, "after", Map.of("name", s.getName(),
                "tin", String.valueOf(s.getTin()), "status", s.getStatus(), "vatRegistered", s.isVatRegistered())));
        return view(s);
    }

    Supplier load(UUID id) {
        return suppliers.findById(id).orElseThrow(() -> ApiException.notFound("Supplier"));
    }

    private void checkEwt(UUID id) {
        if (id != null) {
            taxCodes.requireActive(id, "EWT");
        }
    }

    private SupplierView view(Supplier s) {
        BigDecimal open = jdbc.queryForObject("SELECT coalesce(sum(payable_amount - paid_amount), 0) FROM supplier_invoices "
                + "WHERE supplier_id = ? AND status = 'POSTED'", BigDecimal.class, s.getId());
        String ewt = s.getDefaultEwtCodeId() == null ? null
                : jdbc.queryForObject("SELECT code FROM tax_codes WHERE id = ?", String.class, s.getDefaultEwtCodeId());
        return new SupplierView(s.getId(), s.getCode(), s.getName(), s.getTin(), s.getAddress(), s.isVatRegistered(),
                s.getDefaultEwtCodeId(), ewt, s.getSubsidiaryAccountId(), s.getStatus(), open, s.getVersion());
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
