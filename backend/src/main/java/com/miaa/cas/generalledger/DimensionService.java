package com.miaa.cas.generalledger;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.generalledger.domain.CostCenter;
import com.miaa.cas.generalledger.domain.CostCenterRepository;
import com.miaa.cas.generalledger.domain.SubsidiaryAccount;
import com.miaa.cas.generalledger.domain.SubsidiaryAccountRepository;
import com.miaa.cas.generalledger.web.LedgerDtos.CostCenterRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.CostCenterResponse;
import com.miaa.cas.generalledger.web.LedgerDtos.DimensionUpdateRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.SubsidiaryAccountRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.SubsidiaryAccountResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cost centres and subsidiary-ledger parties: the analytical dimensions of journal lines. */
@Service
public class DimensionService {

    private final CostCenterRepository costCenters;
    private final SubsidiaryAccountRepository subsidiaries;
    private final AuditService audit;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public DimensionService(CostCenterRepository costCenters, SubsidiaryAccountRepository subsidiaries, AuditService audit,
            Clock clock, JdbcTemplate jdbc) {
        this.costCenters = costCenters;
        this.subsidiaries = subsidiaries;
        this.audit = audit;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<CostCenterResponse> costCenters() {
        return costCenters.findAllByOrderByCodeAsc().stream().map(DimensionService::toResponse).toList();
    }

    @Transactional
    public CostCenterResponse createCostCenter(CostCenterRequest req, boolean placeholder, Actor actor) {
        if (costCenters.existsByCode(req.code())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Cost centre code already exists.");
        }
        CostCenter c = costCenters.saveAndFlush(new CostCenter(UUID.randomUUID(), req.code(), req.name().trim(), placeholder,
                Instant.now(clock), actor.username()));
        audit.record(actor, "COST_CENTER_CREATED", "COST_CENTER", c.getCode(), Map.of("name", c.getName()));
        return toResponse(c);
    }

    @Transactional
    public CostCenterResponse updateCostCenter(UUID id, DimensionUpdateRequest req) {
        CostCenter c = costCenters.findById(id).orElseThrow(() -> ApiException.notFound("Cost centre"));
        if (c.getVersion() != req.version()) {
            throw ApiException.staleVersion();
        }
        Map<String, Object> before = Map.of("name", c.getName(), "status", c.getStatus());
        c.update(req.name().trim(), req.status());
        costCenters.flush();
        audit.record("COST_CENTER_UPDATED", "COST_CENTER", c.getCode(), Map.of("before", before,
                "after", Map.of("name", c.getName(), "status", c.getStatus())));
        return toResponse(c);
    }

    @Transactional(readOnly = true)
    public PageResponse<SubsidiaryAccountResponse> subsidiaryAccounts(String slType, String q, int page, int size) {
        String type = slType == null || slType.isBlank() ? null : slType.trim().toUpperCase();
        String like = JdbcPaging.like(q);
        return JdbcPaging.ids(jdbc, "SELECT id FROM subsidiary_ledger_accounts WHERE (?::text IS NULL OR sl_type = ?::text) "
                + "AND (?::text IS NULL OR lower(code || ' ' || name) LIKE ?::text) ORDER BY sl_type, code, id", page, size, type, type, like, like)
                .map(id -> toResponse(subsidiaries.findById(id).orElseThrow()));
    }

    @Transactional(readOnly = true)
    public SubsidiaryAccountResponse subsidiaryAccount(UUID id) {
        return toResponse(subsidiaries.findById(id).orElseThrow(() -> ApiException.notFound("Subsidiary account")));
    }

    @Transactional
    public SubsidiaryAccountResponse createSubsidiaryAccount(SubsidiaryAccountRequest req) {
        if (subsidiaries.existsBySlTypeAndCode(req.slType(), req.code())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A subsidiary account with this type and code exists.");
        }
        SubsidiaryAccount s = subsidiaries.saveAndFlush(new SubsidiaryAccount(UUID.randomUUID(), req.slType(), req.code(),
                req.name().trim(), Instant.now(clock), Actor.current().username()));
        audit.record("SUBSIDIARY_ACCOUNT_CREATED", "SUBSIDIARY_ACCOUNT", s.getId(), Map.of("type", s.getSlType(),
                "code", s.getCode()));
        return toResponse(s);
    }

    @Transactional
    public SubsidiaryAccountResponse updateSubsidiaryAccount(UUID id, DimensionUpdateRequest req) {
        SubsidiaryAccount s = subsidiaries.findById(id).orElseThrow(() -> ApiException.notFound("Subsidiary account"));
        if (s.getVersion() != req.version()) {
            throw ApiException.staleVersion();
        }
        Map<String, Object> before = Map.of("name", s.getName(), "status", s.getStatus());
        s.update(req.name().trim(), req.status());
        subsidiaries.flush();
        audit.record("SUBSIDIARY_ACCOUNT_UPDATED", "SUBSIDIARY_ACCOUNT", s.getId(), Map.of("before", before,
                "after", Map.of("name", s.getName(), "status", s.getStatus())));
        return toResponse(s);
    }

    static CostCenterResponse toResponse(CostCenter c) {
        return new CostCenterResponse(c.getId(), c.getCode(), c.getName(), c.getStatus(), c.isPlaceholder(), c.getVersion());
    }

    static SubsidiaryAccountResponse toResponse(SubsidiaryAccount s) {
        return new SubsidiaryAccountResponse(s.getId(), s.getSlType(), s.getCode(), s.getName(), s.getStatus(), s.getVersion());
    }
}
