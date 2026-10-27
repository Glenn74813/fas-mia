package com.miaa.cas.audit.anomaly.web;

import com.miaa.cas.audit.anomaly.AnomalyDetectionService;
import com.miaa.cas.audit.anomaly.AnomalyReviewService;
import com.miaa.cas.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/anomalies")
@Tag(name = "Anomalies", description = "Suspicious-activity findings (TOR §2.2.3) and their review")
public class AnomalyController {

    public record ReviewRequest(@NotNull Long version, @NotBlank String status, @NotBlank @Size(max = 2000) String note) {
    }

    private final AnomalyReviewService findings;
    private final AnomalyDetectionService detection;

    public AnomalyController(AnomalyReviewService findings, AnomalyDetectionService detection) {
        this.findings = findings;
        this.detection = detection;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_ANOMALY_VIEW')")
    public PageResponse<AnomalyReviewService.Finding> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String severity, @RequestParam(required = false) String rule,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return findings.list(status, severity, rule, page, size);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('PERM_ANOMALY_VIEW')")
    public AnomalyReviewService.Summary summary() {
        return findings.summary();
    }

    @PostMapping("/run")
    @PreAuthorize("hasAuthority('PERM_ANOMALY_REVIEW')")
    @Operation(summary = "Run detection now (it also runs every hour)")
    public AnomalyDetectionService.RunResult run() {
        return detection.run();
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('PERM_ANOMALY_REVIEW')")
    @Operation(summary = "Review a finding as EXPLAINED or ESCALATED, with a note")
    public AnomalyReviewService.Finding review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        return findings.review(id, request.status(), request.note(), request.version());
    }
}
