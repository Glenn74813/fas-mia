package com.miaa.cas.integrations.payroll.web;

import com.miaa.cas.integrations.payroll.GeneralPayrollService;
import com.miaa.cas.integrations.payroll.PayrollDtos.ColumnMapping;
import com.miaa.cas.integrations.payroll.PayrollDtos.ColumnMappingRequest;
import com.miaa.cas.integrations.payroll.PayrollDtos.ImportResult;
import com.miaa.cas.integrations.payroll.PayrollDtos.ImportView;
import com.miaa.cas.integrations.payroll.PayrollDtos.Preview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/payroll")
@Tag(name = "General payroll", description = "Book the HRIS General Payroll as a draft journal voucher through a column-to-account mapping")
public class PayrollController {

    private final GeneralPayrollService payroll;

    public PayrollController(GeneralPayrollService payroll) {
        this.payroll = payroll;
    }

    @GetMapping("/column-mappings")
    @PreAuthorize("hasAuthority('PERM_JOURNAL_CREATE') or hasAuthority('PERM_LEDGER_VIEW')")
    public List<ColumnMapping> mappings() {
        return payroll.mappings();
    }

    @PutMapping("/column-mappings")
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    @Operation(summary = "Add a General Payroll column, or change the account or treatment of one, matched by its heading")
    public ColumnMapping saveMapping(@Valid @RequestBody ColumnMappingRequest request) {
        return payroll.saveMapping(request);
    }

    @PostMapping(value = "/general-payroll/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_JOURNAL_CREATE')")
    @Operation(summary = "Show the voucher a General Payroll file would create and every problem with it; nothing is saved")
    public Preview preview(@RequestPart("file") MultipartFile file,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate accountingDate,
            @RequestParam(required = false) UUID costCenterId) throws IOException {
        return payroll.preview(file.getOriginalFilename(), file.getBytes(), accountingDate, costCenterId);
    }

    @PostMapping(value = "/general-payroll", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_JOURNAL_CREATE')")
    @Operation(summary = "Import a General Payroll file as one draft journal voucher with the file attached")
    public ImportResult importFile(@RequestPart("file") MultipartFile file,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate accountingDate,
            @RequestParam(required = false) UUID costCenterId, @RequestParam(required = false) String reference) throws IOException {
        return payroll.importFile(file.getOriginalFilename(), file.getBytes(), accountingDate, costCenterId, reference);
    }

    @GetMapping("/general-payroll")
    @PreAuthorize("hasAuthority('PERM_JOURNAL_CREATE') or hasAuthority('PERM_LEDGER_VIEW')")
    public List<ImportView> imports() {
        return payroll.imports();
    }
}
