package com.miaa.cas.journal;

import com.miaa.cas.journal.domain.VoucherType;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads the configurable approval chain (approval_workflows / approval_workflow_steps). */
@Component
public class WorkflowConfig {

    public record Step(int order, String code, String permission, BigDecimal minAmount) {

        boolean appliesTo(BigDecimal total) {
            return minAmount == null || total.compareTo(minAmount) >= 0;
        }
    }

    private final JdbcTemplate jdbc;

    public WorkflowConfig(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean requiresAttachment(VoucherType type) {
        Boolean b = jdbc.queryForObject("SELECT requires_attachment FROM approval_workflows WHERE voucher_type = ?",
                Boolean.class, type.name());
        return Boolean.TRUE.equals(b);
    }

    /** Steps that apply to a voucher of this type and total, in order. */
    public List<Step> steps(VoucherType type, BigDecimal total) {
        return jdbc.query("SELECT step_order, step_code, required_permission, min_amount FROM approval_workflow_steps "
                + "WHERE voucher_type = ? ORDER BY step_order", (rs, i) -> new Step(rs.getInt(1), rs.getString(2),
                rs.getString(3), rs.getBigDecimal(4)), type.name()).stream().filter(s -> s.appliesTo(total)).toList();
    }
}
