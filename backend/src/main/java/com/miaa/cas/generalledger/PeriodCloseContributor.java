package com.miaa.cas.generalledger;

import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.web.LedgerDtos.CloseCheck;
import java.util.List;

/**
 * Lets modules add their own period-close checks (unposted vouchers now; bank reconciliations,
 * depreciation and sub-ledger reconciliations in Phase 3) without the ledger depending on them.
 */
public interface PeriodCloseContributor {

    List<CloseCheck> checks(AccountingPeriod period);
}
