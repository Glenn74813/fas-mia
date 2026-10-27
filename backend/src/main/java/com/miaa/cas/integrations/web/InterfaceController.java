package com.miaa.cas.integrations.web;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.integrations.inbound.InterfaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/interfaces")
@Tag(name = "Inbound interfaces", description = "Payroll, FMIS budget and asset files (CSV, JSON, XML) validated into batches and accepted as drafts")
public class InterfaceController {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final String SENDERS = "hasAnyAuthority('PERM_INTERFACE_SUBMIT', 'PERM_JOURNAL_CREATE', 'PERM_BUDGET_PREPARE', 'PERM_FA_MANAGE')";

    public record VersionRequest(long version) {
    }

    public record RejectRequest(@NotBlank @Size(max = 500) String note, long version) {
    }

    private final InterfaceService interfaces;

    public InterfaceController(InterfaceService interfaces) {
        this.interfaces = interfaces;
    }

    /** The file as the request body; its Content-Type (text/csv, application/json, application/xml) gives the format. */
    @PostMapping(value = "/{type}/batches", consumes = {"text/csv", MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE,
        MediaType.TEXT_XML_VALUE})
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SENDERS)
    @Operation(summary = "Send an interface file as the request body; it is validated into a batch, nothing is created yet")
    public InterfaceService.BatchView submit(@PathVariable String type, @RequestParam(required = false) String sourceReference,
            @RequestParam(required = false) String accountingDate, @RequestParam(required = false) String period,
            @RequestParam(required = false) String fiscalYear, @RequestParam(required = false) String offsetAccountCode,
            HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        return interfaces.submit(type.toUpperCase(Locale.ROOT), format(request.getContentType()), limit(body),
                header(sourceReference, accountingDate, period, fiscalYear, offsetAccountCode));
    }

    @PostMapping(value = "/{type}/batches/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SENDERS)
    @Operation(summary = "Upload an interface file (.csv, .json or .xml) from the screens")
    public InterfaceService.BatchView upload(@PathVariable String type, @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String sourceReference, @RequestParam(required = false) String accountingDate,
            @RequestParam(required = false) String period, @RequestParam(required = false) String fiscalYear,
            @RequestParam(required = false) String offsetAccountCode) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String format = name.endsWith(".csv") ? "CSV" : name.endsWith(".json") ? "JSON" : name.endsWith(".xml") ? "XML" : null;
        if (format == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Upload a .csv, .json or .xml file.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The file is larger than 5 MB.");
        }
        return interfaces.submit(type.toUpperCase(Locale.ROOT), format, file.getBytes(),
                header(sourceReference, accountingDate, period, fiscalYear, offsetAccountCode));
    }

    @GetMapping("/batches")
    @PreAuthorize("hasAnyAuthority('PERM_INTEGRATION_VIEW', 'PERM_JOURNAL_CREATE', 'PERM_BUDGET_PREPARE', 'PERM_FA_MANAGE')")
    public PageResponse<InterfaceService.BatchView> list(@RequestParam(required = false) String type, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return interfaces.list(type, status, page, size);
    }

    @GetMapping("/batches/{id}")
    @PreAuthorize(SENDERS + " or hasAuthority('PERM_INTEGRATION_VIEW')")
    public InterfaceService.BatchView get(@PathVariable UUID id) {
        return interfaces.get(id);
    }

    @PostMapping("/batches/{id}/accept")
    @PreAuthorize("hasAnyAuthority('PERM_JOURNAL_CREATE', 'PERM_BUDGET_PREPARE', 'PERM_FA_MANAGE')")
    @Operation(summary = "Create the drafts from a validated batch (a journal voucher, budget allocations or fixed assets)")
    public InterfaceService.BatchView accept(@PathVariable UUID id, @RequestBody VersionRequest r) {
        return interfaces.accept(id, r.version());
    }

    @PostMapping("/batches/{id}/reject")
    @PreAuthorize("hasAnyAuthority('PERM_JOURNAL_CREATE', 'PERM_BUDGET_PREPARE', 'PERM_FA_MANAGE')")
    public InterfaceService.BatchView reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest r) {
        return interfaces.reject(id, r.note(), r.version());
    }

    private static String format(String contentType) {
        String t = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (t.startsWith("text/csv")) {
            return "CSV";
        }
        if (t.startsWith("application/json")) {
            return "JSON";
        }
        return "XML";
    }

    private static byte[] limit(byte[] body) {
        if (body.length > MAX_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The file is larger than 5 MB.");
        }
        return body;
    }

    private static Map<String, String> header(String reference, String accountingDate, String period, String fiscalYear, String offset) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("reference", reference);
        h.put("accountingDate", accountingDate);
        h.put("period", period);
        h.put("fiscalYear", fiscalYear);
        h.put("offsetAccountCode", offset);
        return h;
    }
}
