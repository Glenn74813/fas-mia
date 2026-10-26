package com.miaa.cas.journal;

import com.miaa.cas.generalledger.PeriodCloseContributor;
import com.miaa.cas.generalledger.PeriodCloseService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.web.LedgerDtos.CloseCheck;
import com.miaa.cas.journal.domain.JournalVoucherRepository;
import com.miaa.cas.journal.domain.VoucherStatus;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class UnpostedVoucherCloseCheck implements PeriodCloseContributor {

    private final JournalVoucherRepository vouchers;

    public UnpostedVoucherCloseCheck(JournalVoucherRepository vouchers) {
        this.vouchers = vouchers;
    }

    @Override
    public List<CloseCheck> checks(AccountingPeriod period) {
        long n = vouchers.countInPeriod(period.getId(), List.of(VoucherStatus.DRAFT, VoucherStatus.SUBMITTED,
                VoucherStatus.APPROVED, VoucherStatus.RETURNED));
        return List.of(new CloseCheck("NO_UNPOSTED_VOUCHERS", "No draft, returned, submitted or approved vouchers remain",
                n == 0 ? PeriodCloseService.PASS : PeriodCloseService.FAIL, true,
                n == 0 ? "" : n + " voucher(s) not yet posted; they will be blocked from posting once the period closes"));
    }
}
