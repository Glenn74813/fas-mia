package com.miaa.cas.fixedassets;

import com.miaa.cas.generalledger.PeriodCloseContributor;
import com.miaa.cas.generalledger.PeriodCloseService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.web.LedgerDtos.CloseCheck;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Close checklist: no active asset has depreciation due through the period that has not been posted. */
@Component
public class DepreciationCloseCheck implements PeriodCloseContributor {

    private final DepreciationService depreciation;
    private final JdbcTemplate jdbc;

    public DepreciationCloseCheck(DepreciationService depreciation, JdbcTemplate jdbc) {
        this.depreciation = depreciation;
        this.jdbc = jdbc;
    }

    @Override
    public List<CloseCheck> checks(AccountingPeriod period) {
        if (period.isAdjustment()) {
            return List.of();
        }
        YearMonth month = YearMonth.from(period.getEndDate());
        List<String> due = new ArrayList<>();
        for (Map<String, Object> a : jdbc.queryForList("SELECT a.*, " + FixedAssetService.ACCUMULATED + " AS acc FROM fixed_assets a "
                + "WHERE a.status = 'ACTIVE' AND a.in_service_date <= ? ORDER BY a.asset_number", Date.valueOf(period.getEndDate()))) {
            if (depreciation.expected(a, month).compareTo((BigDecimal) a.get("acc")) > 0) {
                due.add(String.valueOf(a.get("asset_number")));
            }
        }
        return List.of(new CloseCheck("DEPRECIATION_POSTED", "Depreciation posted for the month",
                due.isEmpty() ? PeriodCloseService.PASS : PeriodCloseService.FAIL, true, due.isEmpty() ? ""
                        : due.size() + " asset(s) have depreciation due through " + month + ", e.g. " + String.join(", ", due.stream().limit(5).toList())));
    }
}
