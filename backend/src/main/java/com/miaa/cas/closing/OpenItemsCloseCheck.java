package com.miaa.cas.closing;

import com.miaa.cas.generalledger.ClearingService;
import com.miaa.cas.generalledger.PeriodCloseContributor;
import com.miaa.cas.generalledger.PeriodCloseService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.web.LedgerDtos.CloseCheck;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Period-close exception report for open-item managed accounts: items still open more than {@value #DAYS} days after
 * they were posted, as of the period end. Waivable, since an old advance or suspense item may be legitimately open.
 */
@Component
public class OpenItemsCloseCheck implements PeriodCloseContributor {

    static final int DAYS = 90;

    private final ClearingService clearing;

    public OpenItemsCloseCheck(ClearingService clearing) {
        this.clearing = clearing;
    }

    @Override
    public List<CloseCheck> checks(AccountingPeriod period) {
        List<String> aged = clearing.agedItems(period.getEndDate(), DAYS, 6);
        String detail = aged.isEmpty() ? "" : (aged.size() > 5 ? "More than 5" : String.valueOf(aged.size())) + " item(s), oldest: "
                + String.join("; ", aged.subList(0, Math.min(5, aged.size())));
        return List.of(new CloseCheck("OPEN_ITEMS_AGED", "No open item on a clearing account is older than " + DAYS + " days",
                aged.isEmpty() ? PeriodCloseService.PASS : PeriodCloseService.FAIL, true, detail));
    }
}
