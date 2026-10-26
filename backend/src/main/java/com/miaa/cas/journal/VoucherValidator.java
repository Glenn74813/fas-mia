package com.miaa.cas.journal;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.config.AccountingProperties;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.generalledger.domain.Account;
import com.miaa.cas.generalledger.domain.AccountRepository;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.domain.CostCenter;
import com.miaa.cas.generalledger.domain.CostCenterRepository;
import com.miaa.cas.generalledger.domain.FiscalYear;
import com.miaa.cas.generalledger.domain.FiscalYearRepository;
import com.miaa.cas.generalledger.domain.PeriodStatus;
import com.miaa.cas.generalledger.domain.SubsidiaryAccount;
import com.miaa.cas.generalledger.domain.SubsidiaryAccountRepository;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.journal.domain.VoucherType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Accounting rules for a voucher. {@link Level#DRAFT} checks line shape only, so incomplete drafts
 * can be saved; {@link Level#FULL} (on submission and again inside the posting transaction) enforces
 * double entry, account and dimension validity, period status and supporting documents.
 */
@Component
public class VoucherValidator {

    public enum Level { DRAFT, FULL }

    private final AccountRepository accounts;
    private final CostCenterRepository costCenters;
    private final SubsidiaryAccountRepository subsidiaries;
    private final FiscalYearRepository years;
    private final WorkflowConfig workflow;
    private final DocumentService documents;
    private final AccountingProperties properties;

    public VoucherValidator(AccountRepository accounts, CostCenterRepository costCenters,
            SubsidiaryAccountRepository subsidiaries, FiscalYearRepository years, WorkflowConfig workflow,
            DocumentService documents, AccountingProperties properties) {
        this.accounts = accounts;
        this.costCenters = costCenters;
        this.subsidiaries = subsidiaries;
        this.years = years;
        this.workflow = workflow;
        this.documents = documents;
        this.properties = properties;
    }

    public void validate(Level level, UUID voucherId, VoucherType type, LocalDate date, AccountingPeriod period,
            List<LineData> lines) {
        List<String> v = new ArrayList<>();
        Map<UUID, Account> accountMap = accounts.findAllById(lines.stream().map(LineData::accountId).toList()).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Map<UUID, CostCenter> ccMap = costCenters.findAllById(lines.stream().map(LineData::costCenterId)
                .filter(java.util.Objects::nonNull).toList()).stream().collect(Collectors.toMap(CostCenter::getId, Function.identity()));
        Map<UUID, SubsidiaryAccount> slMap = subsidiaries.findAllById(lines.stream().map(LineData::subsidiaryAccountId)
                .filter(java.util.Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(SubsidiaryAccount::getId, Function.identity()));

        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        int n = 0;
        for (LineData l : lines) {
            n++;
            String at = "Line " + n + ": ";
            boolean d = l.debit() != null && l.debit().signum() > 0;
            boolean c = l.credit() != null && l.credit().signum() > 0;
            if (d == c || (l.debit() != null && l.debit().signum() < 0) || (l.credit() != null && l.credit().signum() < 0)) {
                v.add(at + "enter either a positive debit or a positive credit.");
            }
            debit = debit.add(l.debit() == null ? BigDecimal.ZERO : l.debit());
            credit = credit.add(l.credit() == null ? BigDecimal.ZERO : l.credit());
            Account a = accountMap.get(l.accountId());
            if (a == null) {
                v.add(at + "account does not exist.");
                continue;
            }
            if (l.costCenterId() != null && !ccMap.containsKey(l.costCenterId())) {
                v.add(at + "cost centre does not exist.");
            }
            if (l.subsidiaryAccountId() != null && !slMap.containsKey(l.subsidiaryAccountId())) {
                v.add(at + "subsidiary account does not exist.");
            }
            if (level == Level.DRAFT) {
                continue;
            }
            if (!a.isPostable()) {
                v.add(at + "account " + a.getCode() + " is a header account and cannot be posted to.");
            }
            if (!a.isActiveOn(date)) {
                v.add(at + "account " + a.getCode() + " is not active on " + date + ".");
            }
            if (a.isPlaceholder() && !properties.allowPlaceholderAccounts()) {
                v.add(at + "account " + a.getCode() + " belongs to the placeholder chart and cannot be used in this environment.");
            }
            CostCenter cc = l.costCenterId() == null ? null : ccMap.get(l.costCenterId());
            if (a.isRequiresCostCenter() && cc == null) {
                v.add(at + "account " + a.getCode() + " requires a cost centre.");
            }
            if (cc != null && !cc.isActive()) {
                v.add(at + "cost centre " + cc.getCode() + " is inactive.");
            }
            SubsidiaryAccount sl = l.subsidiaryAccountId() == null ? null : slMap.get(l.subsidiaryAccountId());
            if (a.getSubsidiaryType() != null && sl == null) {
                v.add(at + "account " + a.getCode() + " requires a " + a.getSubsidiaryType().toLowerCase() + " subsidiary account.");
            }
            if (sl != null && a.getSubsidiaryType() == null) {
                v.add(at + "account " + a.getCode() + " does not keep a subsidiary ledger.");
            }
            if (sl != null && a.getSubsidiaryType() != null && !a.getSubsidiaryType().equals(sl.getSlType())) {
                v.add(at + "subsidiary account " + sl.getCode() + " is a " + sl.getSlType() + ", expected " + a.getSubsidiaryType() + ".");
            }
            if (sl != null && !sl.isActive()) {
                v.add(at + "subsidiary account " + sl.getCode() + " is inactive.");
            }
        }

        if (period.getStatus() == PeriodStatus.CLOSED) {
            v.add("Period " + period.getName() + " is closed.");
        }
        if (level == Level.FULL) {
            if (lines.size() < 2) {
                v.add("A journal entry needs at least two lines.");
            }
            if (debit.compareTo(credit) != 0) {
                v.add("Debits (" + debit.toPlainString() + ") do not equal credits (" + credit.toPlainString() + ").");
            }
            if (debit.signum() == 0) {
                v.add("The voucher total must be greater than zero.");
            }
            switch (period.getStatus()) {
                case OPEN -> { }
                case SOFT_CLOSED -> {
                    if (!type.allowedInSoftClosedPeriod()) {
                        v.add("Period " + period.getName() + " is soft-closed; only adjusting and closing vouchers may post.");
                    }
                }
                case FUTURE -> v.add("Period " + period.getName() + " has not been opened.");
                default -> { }
            }
            FiscalYear fy = years.findById(period.getFiscalYearId()).orElseThrow();
            if (!fy.isOpen()) {
                v.add("Fiscal year " + fy.getFiscalYear() + " is closed.");
            }
            if (type == VoucherType.OPENING && period.getPeriodNumber() != 1) {
                v.add("Opening-balance vouchers must be dated in the first period of the fiscal year.");
            }
            if (type == VoucherType.CLOSING && !period.isAdjustment()) {
                v.add("Closing vouchers must post to the adjustment period.");
            }
            if (period.isAdjustment() && type != VoucherType.ADJUSTING && type != VoucherType.CLOSING
                    && type != VoucherType.REVERSAL) {
                v.add("Only adjusting, closing and reversal vouchers may use the adjustment period.");
            }
            if (workflow.requiresAttachment(type) && documents.activeCount("JOURNAL_VOUCHER", voucherId) == 0) {
                v.add("Attach at least one supporting document.");
            }
        }
        if (!v.isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "The voucher does not satisfy accounting rules.",
                    Map.of("violations", v));
        }
    }
}
