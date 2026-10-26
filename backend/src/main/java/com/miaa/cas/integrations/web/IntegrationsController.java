package com.miaa.cas.integrations.web;

import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.integrations.TransmissionService;
import com.miaa.cas.integrations.TransmissionService.ChannelView;
import com.miaa.cas.integrations.TransmissionService.TransmissionView;
import com.miaa.cas.integrations.bir.EisDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations")
@Tag(name = "Integrations", description = "Transmission log, retries and the BIR EIS port (sandbox only)")
public class IntegrationsController {

    public record QueueDocumentRequest(@NotNull @Pattern(regexp = "AR_BILLING|AR_RECEIPT") String documentType, @NotNull UUID documentId) {
    }

    public record QueueRangeRequest(@NotNull LocalDate from, @NotNull LocalDate to) {
    }

    public record CancelRequest(@NotBlank @Size(min = 5, max = 500) String reason) {
    }

    private final TransmissionService transmissions;
    private final EisDocumentService eis;

    public IntegrationsController(TransmissionService transmissions, EisDocumentService eis) {
        this.transmissions = transmissions;
        this.eis = eis;
    }

    @GetMapping("/channels")
    @PreAuthorize("hasAuthority('PERM_INTEGRATION_VIEW')")
    public List<ChannelView> channels() {
        return transmissions.channels();
    }

    @GetMapping("/transmissions")
    @PreAuthorize("hasAuthority('PERM_INTEGRATION_VIEW')")
    public PageResponse<TransmissionView> transmissions(@RequestParam(required = false) String channel,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return transmissions.list(channel, status, page, size);
    }

    @GetMapping("/transmissions/{id}")
    @PreAuthorize("hasAuthority('PERM_INTEGRATION_VIEW')")
    public TransmissionView transmission(@PathVariable UUID id) {
        return transmissions.get(id);
    }

    @PostMapping("/transmissions/{id}/retry")
    @PreAuthorize("hasAuthority('PERM_INTEGRATION_MANAGE')")
    public TransmissionView retry(@PathVariable UUID id) {
        return transmissions.retry(id);
    }

    @PostMapping("/transmissions/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_INTEGRATION_MANAGE')")
    public TransmissionView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return transmissions.cancel(id, request.reason());
    }

    @PostMapping("/bir-eis/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_INTEGRATION_MANAGE')")
    @Operation(summary = "Queue a posted billing document or receipt on the BIR EIS channel (idempotent per document)")
    public TransmissionView queueDocument(@Valid @RequestBody QueueDocumentRequest request) {
        return eis.queue(request.documentType(), request.documentId());
    }

    @PostMapping("/bir-eis/posted")
    @PreAuthorize("hasAuthority('PERM_INTEGRATION_MANAGE')")
    public Map<String, Integer> queuePosted(@Valid @RequestBody QueueRangeRequest request) {
        return Map.of("queued", eis.queuePosted(request.from(), request.to()));
    }
}
