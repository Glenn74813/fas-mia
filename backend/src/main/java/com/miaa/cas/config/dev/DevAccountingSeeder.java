package com.miaa.cas.config.dev;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.generalledger.AccountService;
import com.miaa.cas.generalledger.DimensionService;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.domain.AccountRepository;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.domain.AccountingPeriodRepository;
import com.miaa.cas.generalledger.domain.CostCenterRepository;
import com.miaa.cas.generalledger.domain.FiscalYearRepository;
import com.miaa.cas.generalledger.domain.PeriodStatus;
import com.miaa.cas.generalledger.web.LedgerDtos.CostCenterRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEVELOPMENT ONLY. On an empty database, loads the PLACEHOLDER chart of accounts and cost centres
 * (flagged placeholder, never MIAA-approved), creates the current fiscal year and opens its periods up
 * to the current month, so the accounting engine can be exercised locally. The placeholder setup is then
 * moved onto COA's Revised Chart of Accounts for Government Corporations ({@link DevOfficialChart}).
 */
@Component
@Profile("dev")
public class DevAccountingSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAccountingSeeder.class);

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final CostCenterRepository costCenters;
    private final DimensionService dimensions;
    private final FiscalYearRepository years;
    private final FiscalCalendarService calendar;
    private final AccountingPeriodRepository periods;
    private final Clock clock;
    private final DevPayablesSeeder payables;
    private final DevOfficialChart officialChart;

    public DevAccountingSeeder(AccountRepository accounts, AccountService accountService, CostCenterRepository costCenters,
            DimensionService dimensions, FiscalYearRepository years, FiscalCalendarService calendar,
            AccountingPeriodRepository periods, Clock clock, DevPayablesSeeder payables, DevOfficialChart officialChart) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.costCenters = costCenters;
        this.dimensions = dimensions;
        this.years = years;
        this.calendar = calendar;
        this.periods = periods;
        this.clock = clock;
        this.payables = payables;
        this.officialChart = officialChart;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        if (accounts.count() == 0) {
            try (InputStream in = new ClassPathResource("seed/placeholder-chart-of-accounts.csv").getInputStream()) {
                var result = accountService.importCsv(in, true, Actor.SYSTEM);
                log.warn("DEV: loaded {} PLACEHOLDER accounts (not MIAA-approved)", result.created());
            }
        }
        if (costCenters.count() == 0) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new ClassPathResource("seed/placeholder-cost-centers.csv").getInputStream(), StandardCharsets.UTF_8))) {
                r.lines().filter(l -> !l.startsWith("#") && !l.startsWith("code,") && !l.isBlank()).forEach(l -> {
                    String[] parts = l.split(",", 2);
                    dimensions.createCostCenter(new CostCenterRequest(parts[0], parts[1]), true, Actor.SYSTEM);
                });
            }
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Manila")));
        if (!years.existsByFiscalYear(today.getYear())) {
            var fy = calendar.create(today.getYear(), Actor.SYSTEM);
            for (AccountingPeriod p : periods.findByFiscalYearIdOrderByPeriodNumberAsc(fy.id())) {
                if (p.getPeriodNumber() <= today.getMonthValue() && p.getStatus() == PeriodStatus.FUTURE) {
                    calendar.openPeriod(p.getId(), p.getVersion(), Actor.SYSTEM);
                }
            }
            log.warn("DEV: created fiscal year {} and opened periods 1-{}", today.getYear(), today.getMonthValue());
        }
        payables.seed();
        officialChart.apply();
    }
}
