package com.miaa.cas.tax;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.generalledger.AccountPurpose;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tax codes (withholding and VAT) are configuration, not code: AVTECH's tax team maintains codes, rates
 * and the accounts they post to. Rates are applied with HALF_EVEN rounding to centavos per line.
 */
@Service
public class TaxCodeService {

    public record TaxCode(UUID id, String code, String description, String taxType, BigDecimal rate, UUID accountId,
            String accountCode, String status, boolean placeholder, String atc) {
    }

    /** One line of the schedule of alphanumeric tax codes on BIR Form 2307. */
    public record Atc(String atc, String section, String payeeType, String description, String condition, BigDecimal printedRate) {
    }

    public record TaxCodeRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9.-]{0,19}$") String code,
            @NotBlank @Size(max = 300) String description,
            @NotNull @Pattern(regexp = "EWT|VAT_INPUT|VAT_OUTPUT|FINAL_VAT") String taxType,
            @NotNull @DecimalMin("0") @DecimalMax("0.9999") @Digits(integer = 1, fraction = 4) BigDecimal rate,
            @NotNull UUID accountId) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public TaxCodeService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TaxCode> list() {
        return jdbc.query("SELECT t.*, a.code AS account_code FROM tax_codes t JOIN chart_of_accounts a ON a.id = t.account_id "
                + "ORDER BY t.tax_type, t.code", (rs, i) -> new TaxCode(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("description"), rs.getString("tax_type"), rs.getBigDecimal("rate"),
                rs.getObject("account_id", UUID.class), rs.getString("account_code"), rs.getString("status"),
                rs.getBoolean("placeholder"), rs.getString("atc")));
    }

    @Transactional(readOnly = true)
    public TaxCode get(UUID id) {
        return list().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Tax code"));
    }

    @Transactional
    public TaxCode create(TaxCodeRequest req, boolean placeholder, Actor actor) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM tax_codes WHERE code = ?)", Boolean.class, req.code());
        if (Boolean.TRUE.equals(exists)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Tax code already exists.");
        }
        AccountPurpose.require(jdbc, req.accountId(), AccountPurpose.forTaxType(req.taxType()), "Tax account");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tax_codes (id, code, description, tax_type, rate, account_id, status, placeholder, created_at, "
                + "created_by) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)", id, req.code(), req.description(), req.taxType(),
                req.rate(), req.accountId(), placeholder, Timestamp.from(Instant.now(clock)), actor.username());
        audit.record(actor, "TAX_CODE_CREATED", "TAX_CODE", req.code(), Map.of("type", req.taxType(),
                "rate", req.rate().toPlainString(), "placeholder", placeholder));
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<Atc> atcCatalogue() {
        return jdbc.query("SELECT * FROM bir_atc_codes ORDER BY section, atc", (rs, i) -> new Atc(rs.getString("atc"), rs.getString("section"),
                rs.getString("payee_type"), rs.getString("description"), rs.getString("condition"), rs.getBigDecimal("printed_rate")));
    }

    /** The BIR alphanumeric tax code printed on Form 2307; AVTECH's tax team assigns it to each withholding code. */
    @Transactional
    public TaxCode setAtc(UUID id, String atc, Actor actor) {
        TaxCode t = get(id);
        String value = atc == null || atc.isBlank() ? null : atc.trim().toUpperCase(java.util.Locale.ROOT);
        if (value != null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bir_atc_codes WHERE atc = ?)", Boolean.class, value))) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, value + " is not on the schedule of alphanumeric tax codes.",
                    Map.of("violations", List.of(value + " is not on the schedule of BIR Form 2307.")));
        }
        jdbc.update("UPDATE tax_codes SET atc = ? WHERE id = ?", value, id);
        audit.record(actor, "TAX_CODE_ATC_SET", "TAX_CODE", t.code(), Map.of("before", String.valueOf(t.atc()), "after", String.valueOf(value)));
        return get(id);
    }

    /** Tax on a base amount, rounded HALF_EVEN to centavos. */
    public static BigDecimal compute(BigDecimal base, BigDecimal rate) {
        return Money.of(base.multiply(rate));
    }

    public TaxCode requireActive(UUID id, String expectedType) {
        TaxCode t = get(id);
        if (!"ACTIVE".equals(t.status()) || !expectedType.equals(t.taxType())) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "Tax code " + t.code() + " is not an active "
                    + expectedType + " code.", Map.of("violations", List.of("Tax code " + t.code() + " cannot be used here.")));
        }
        return t;
    }
}
