package com.miaa.cas.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.generalledger.AccountService;
import com.miaa.cas.generalledger.DimensionService;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.domain.AccountRepository;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.domain.AccountingPeriodRepository;
import com.miaa.cas.generalledger.domain.CostCenterRepository;
import com.miaa.cas.generalledger.domain.SubsidiaryAccount;
import com.miaa.cas.generalledger.domain.SubsidiaryAccountRepository;
import com.miaa.cas.generalledger.web.LedgerDtos.CostCenterRequest;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Fixture for accounting tests: the placeholder chart, placeholder cost centres, one customer
 * subsidiary account, a fresh fully-open fiscal year per test, and a four-person team (preparer,
 * reviewer, approver, poster) so segregation of duties can be exercised realistically.
 */
public abstract class AbstractAccountingIT extends AbstractIntegrationTest {

    private static final AtomicInteger YEARS = new AtomicInteger(2100);
    public static final byte[] PDF = "%PDF-1.4\n% test supporting document\n%%EOF\n".getBytes();

    @Autowired protected AccountService accountService;
    @Autowired protected AccountRepository accounts;
    @Autowired protected DimensionService dimensionService;
    @Autowired protected CostCenterRepository costCenters;
    @Autowired protected SubsidiaryAccountRepository subsidiaries;
    @Autowired protected FiscalCalendarService calendar;
    @Autowired protected AccountingPeriodRepository periods;

    protected Session preparer;
    protected Session reviewer;
    protected Session approver;
    protected Session poster;
    protected TestUser preparerUser;

    @BeforeEach
    void accountingFixture() throws Exception {
        tx.executeWithoutResult(s -> {
            if (accounts.count() == 0) {
                try (InputStream in = new ClassPathResource("seed/placeholder-chart-of-accounts.csv").getInputStream()) {
                    accountService.importCsv(in, true, Actor.SYSTEM);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                dimensionService.createCostCenter(new CostCenterRequest("PLC-CC-FIN", "[PLACEHOLDER] Finance"), true, Actor.SYSTEM);
                dimensionService.createCostCenter(new CostCenterRequest("PLC-CC-OPS", "[PLACEHOLDER] Operations"), true, Actor.SYSTEM);
                subsidiaries.save(new SubsidiaryAccount(UUID.randomUUID(), "CUSTOMER", "CUST-001", "Test Concessionaire",
                        Instant.now(), "test"));
            }
        });
        preparerUser = createUser("prep", "ACCOUNTANT");
        preparer = login(preparerUser);
        reviewer = login(createUser("rev", "ACCOUNTANT"));
        approver = login(createUser("appr", "CHIEF_ACCOUNTANT"));
        poster = login(createUser("post", "CHIEF_ACCOUNTANT"));
    }

    /** Creates a fiscal year unique to the calling test and opens all 13 periods. */
    protected int newOpenYear() {
        int year = YEARS.incrementAndGet();
        tx.executeWithoutResult(s -> {
            var fy = calendar.create(year, Actor.SYSTEM);
            for (AccountingPeriod p : periods.findByFiscalYearIdOrderByPeriodNumberAsc(fy.id())) {
                calendar.openPeriod(p.getId(), p.getVersion(), Actor.SYSTEM);
            }
        });
        return year;
    }

    protected AccountingPeriod period(int year, int number) {
        return periods.findAll().stream().filter(p -> p.getSequenceKey() == year * 100 + number).findFirst().orElseThrow();
    }

    protected UUID account(String code) {
        return accounts.findByCode(code).orElseThrow().getId();
    }

    protected UUID costCenter(String code) {
        return costCenters.findAll().stream().filter(c -> c.getCode().equals(code)).findFirst().orElseThrow().getId();
    }

    protected UUID customer() {
        return subsidiaries.findAll().stream().filter(s -> s.getCode().equals("CUST-001")).findFirst().orElseThrow().getId();
    }

    protected static Map<String, Object> line(UUID accountId, String debit, String credit) {
        return line(accountId, null, null, debit, credit);
    }

    protected static Map<String, Object> line(UUID accountId, UUID costCenterId, UUID subsidiaryId, String debit, String credit) {
        Map<String, Object> m = new HashMap<>();
        m.put("accountId", accountId);
        m.put("costCenterId", costCenterId);
        m.put("subsidiaryAccountId", subsidiaryId);
        m.put("debit", debit);
        m.put("credit", credit);
        return m;
    }

    protected Map<String, Object> voucher(String type, LocalDate date, List<Map<String, Object>> lines) {
        Map<String, Object> m = new HashMap<>();
        m.put("voucherType", type);
        m.put("accountingDate", date.toString());
        m.put("adjustmentPeriod", false);
        m.put("description", "Test voucher " + date);
        m.put("lines", lines);
        return m;
    }

    /** Standard balanced voucher: utilities expense (with cost centre) against cash in bank. */
    protected List<Map<String, Object>> utilitiesLines(String amount) {
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(line(account("PLC-5201"), costCenter("PLC-CC-FIN"), null, amount, "0"));
        lines.add(line(account("PLC-1102"), "0", amount));
        return lines;
    }

    protected JsonNode createVoucher(Session who, Map<String, Object> body) throws Exception {
        return body(mvc.perform(post("/api/v1/journal-vouchers").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body))).andExpect(status().isCreated()).andReturn());
    }

    protected void attachPdf(Session who, String voucherId) throws Exception {
        mvc.perform(multipart("/api/v1/journal-vouchers/" + voucherId + "/documents")
                .file(new MockMultipartFile("file", "invoice.pdf", "application/pdf", PDF))
                .header("Authorization", who.bearer())).andExpect(status().isCreated());
    }

    protected ResultActions action(Session who, String voucherId, String action, long version, String comment) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("comment", comment);
        return mvc.perform(post("/api/v1/journal-vouchers/" + voucherId + "/" + action).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    protected ResultActions postVoucher(Session who, String voucherId, long version, String key) throws Exception {
        var req = post("/api/v1/journal-vouchers/" + voucherId + "/post").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", version)));
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    protected JsonNode ok(ResultActions actions) throws Exception {
        MvcResult r = actions.andExpect(status().isOk()).andReturn();
        return body(r);
    }

    /** Creates, documents, submits, reviews, approves and posts a voucher; returns the posted detail. */
    protected JsonNode postThroughWorkflow(Map<String, Object> voucherBody) throws Exception {
        JsonNode v = createVoucher(preparer, voucherBody);
        String id = v.get("id").asText();
        if (v.get("requiresAttachment").asBoolean()) {
            attachPdf(preparer, id);
        }
        v = ok(action(preparer, id, "submit", v.get("version").asLong(), null));
        v = ok(action(reviewer, id, "approve", v.get("version").asLong(), "Reviewed"));
        v = ok(action(approver, id, "approve", v.get("version").asLong(), "Approved"));
        return ok(postVoucher(poster, id, v.get("version").asLong(), "key-" + UUID.randomUUID()));
    }
}
