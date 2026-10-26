package com.miaa.cas.approvals;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.journal.DecisionLog;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Configurable approval chains for sub-ledger documents ({@code document_workflow_steps}). The owning
 * module keeps its own status; this component answers "which step is next", authorises the current
 * actor for a step (permission, not the preparer, one step per person per cycle) and records the
 * append-only decision.
 */
@Component
public class DocumentWorkflow {

    public record Step(int order, String code, String permission, BigDecimal minAmount) {

        boolean appliesTo(BigDecimal total) {
            return minAmount == null || total.compareTo(minAmount) >= 0;
        }
    }

    private final JdbcTemplate jdbc;
    private final DecisionLog decisions;
    private final ApprovalLimitService limits;

    public DocumentWorkflow(JdbcTemplate jdbc, DecisionLog decisions, ApprovalLimitService limits) {
        this.jdbc = jdbc;
        this.decisions = decisions;
        this.limits = limits;
    }

    public List<Step> steps(String documentType, BigDecimal total) {
        return jdbc.query("SELECT step_order, step_code, required_permission, min_amount FROM document_workflow_steps "
                + "WHERE document_type = ? ORDER BY step_order", (rs, i) -> new Step(rs.getInt(1), rs.getString(2),
                rs.getString(3), rs.getBigDecimal(4)), documentType).stream().filter(s -> s.appliesTo(total)).toList();
    }

    /** Records the submission and returns the first step, or null when no approval step applies. */
    public Step submit(String documentType, UUID documentId, BigDecimal total, String comment) {
        decisions.record(documentType, documentId, "SUBMIT", null, null, CurrentActor.userId(), CurrentActor.username(),
                comment);
        return steps(documentType, total).stream().findFirst().orElse(null);
    }

    /**
     * Authorises and records approval of the current step.
     *
     * @return the next step, or null when the chain is complete
     */
    public Step approve(String documentType, UUID documentId, BigDecimal total, Integer currentStep, UUID preparerId,
            String comment) {
        Step step = current(documentType, total, currentStep);
        CurrentActor.requirePermission(step.permission());
        UUID actor = CurrentActor.userId();
        if (actor.equals(preparerId)) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES,
                    "You prepared this document and cannot " + step.code().toLowerCase() + " it.");
        }
        if (decisions.approversInCurrentCycle(documentType, documentId).contains(actor)) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES,
                    "You already acted on an earlier step of this document; another person must complete this step.");
        }
        limits.require(documentType, total);
        decisions.record(documentType, documentId, "APPROVE", step.order(), step.code(), actor, CurrentActor.username(),
                comment);
        return steps(documentType, total).stream().filter(s -> s.order() > step.order()).findFirst().orElse(null);
    }

    /** Authorises a return or rejection by someone entitled to act on the current step. */
    public void returnOrReject(String documentType, UUID documentId, BigDecimal total, Integer currentStep,
            UUID preparerId, String comment, boolean reject) {
        if (comment == null || comment.trim().length() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A comment of at least 5 characters is required.");
        }
        Step step = current(documentType, total, currentStep);
        CurrentActor.requirePermission(step.permission());
        if (CurrentActor.userId().equals(preparerId)) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "The preparer cannot " + (reject ? "reject" : "return")
                    + " their own document; cancel it instead.");
        }
        decisions.record(documentType, documentId, reject ? "REJECT" : "RETURN", step.order(), step.code(),
                CurrentActor.userId(), CurrentActor.username(), comment.trim());
    }

    public void record(String documentType, UUID documentId, String action, String comment) {
        decisions.record(documentType, documentId, action, null, null, CurrentActor.userId(), CurrentActor.username(),
                comment);
    }

    public List<DecisionLog.Decision> history(String documentType, UUID documentId) {
        return decisions.history(documentType, documentId);
    }

    public String currentStepCode(String documentType, BigDecimal total, Integer currentStep) {
        if (currentStep == null) {
            return null;
        }
        return steps(documentType, total).stream().filter(s -> s.order() == currentStep).map(Step::code).findFirst()
                .orElse(null);
    }

    /** True when the current user could take the current step (UI hint). */
    public boolean canAct(String documentType, UUID documentId, BigDecimal total, Integer currentStep, UUID preparerId) {
        if (currentStep == null || CurrentActor.userId().equals(preparerId)) {
            return false;
        }
        return steps(documentType, total).stream().filter(s -> s.order() == currentStep).findFirst()
                .map(s -> CurrentActor.hasPermission(s.permission()) && limits.allows(documentType, total)
                        && !decisions.approversInCurrentCycle(documentType, documentId).contains(CurrentActor.userId()))
                .orElse(false);
    }

    private Step current(String documentType, BigDecimal total, Integer currentStep) {
        if (currentStep == null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The document is not awaiting approval.");
        }
        return steps(documentType, total).stream().filter(s -> s.order() == currentStep).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE,
                        "The approval step is no longer configured; return the document to its preparer."));
    }
}
