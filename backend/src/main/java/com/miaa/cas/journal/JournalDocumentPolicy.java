package com.miaa.cas.journal;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.documents.DocumentOwnerPolicy;
import com.miaa.cas.journal.domain.JournalVoucher;
import com.miaa.cas.journal.domain.JournalVoucherRepository;
import com.miaa.cas.security.CurrentActor;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Only the preparer may change attachments, and only while the voucher is editable. */
@Component
public class JournalDocumentPolicy implements DocumentOwnerPolicy {

    private final JournalVoucherRepository vouchers;

    public JournalDocumentPolicy(JournalVoucherRepository vouchers) {
        this.vouchers = vouchers;
    }

    @Override
    public String ownerType() {
        return DecisionLog.JOURNAL_VOUCHER;
    }

    @Override
    public void assertCanModify(UUID ownerId) {
        JournalVoucher v = vouchers.findById(ownerId).orElseThrow(() -> ApiException.notFound("Journal voucher"));
        CurrentActor.requirePermission("DOCUMENT_UPLOAD");
        if (!CurrentActor.userId().equals(v.getPreparedById())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer can change this voucher's documents.");
        }
        if (!v.getStatus().isEditable()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Documents can only change while the voucher is a draft or returned.");
        }
    }

    @Override
    public void assertCanView(UUID ownerId) {
        JournalVoucher v = vouchers.findById(ownerId).orElseThrow(() -> ApiException.notFound("Journal voucher"));
        if (!CurrentActor.hasPermission("LEDGER_VIEW") && !CurrentActor.userId().equals(v.getPreparedById())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You do not have permission to view this voucher.");
        }
    }
}
