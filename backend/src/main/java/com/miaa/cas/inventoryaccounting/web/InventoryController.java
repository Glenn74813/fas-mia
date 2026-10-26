package com.miaa.cas.inventoryaccounting.web;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.inventoryaccounting.InvDtos.DocumentRequest;
import com.miaa.cas.inventoryaccounting.InvDtos.DocumentSummary;
import com.miaa.cas.inventoryaccounting.InvDtos.DocumentView;
import com.miaa.cas.inventoryaccounting.InvDtos.GrIrStatus;
import com.miaa.cas.inventoryaccounting.InvDtos.InventoryReconciliation;
import com.miaa.cas.inventoryaccounting.InvDtos.ItemRequest;
import com.miaa.cas.inventoryaccounting.InvDtos.ItemView;
import com.miaa.cas.inventoryaccounting.InvDtos.MatchRequest;
import com.miaa.cas.inventoryaccounting.InvDtos.MovementView;
import com.miaa.cas.inventoryaccounting.InvDtos.VersionRequest;
import com.miaa.cas.inventoryaccounting.InvDtos.WarehouseRequest;
import com.miaa.cas.inventoryaccounting.InvDtos.WarehouseView;
import com.miaa.cas.inventoryaccounting.InventoryDocumentService;
import com.miaa.cas.inventoryaccounting.InventoryReportService;
import com.miaa.cas.inventoryaccounting.InventorySetupService;
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
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Items, warehouses, stock documents at weighted average cost, GR/IR and reconciliation")
public class InventoryController {

    private final InventorySetupService setup;
    private final InventoryDocumentService documents;
    private final InventoryReportService reports;

    public InventoryController(InventorySetupService setup, InventoryDocumentService documents, InventoryReportService reports) {
        this.setup = setup;
        this.documents = documents;
        this.reports = reports;
    }

    @GetMapping("/warehouses")
    @PreAuthorize("hasAuthority('PERM_INVENTORY_VIEW')")
    public List<WarehouseView> warehouses() {
        return setup.warehouses();
    }

    @PostMapping("/warehouses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_INVENTORY_MANAGE')")
    public WarehouseView createWarehouse(@Valid @RequestBody WarehouseRequest request) {
        return setup.createWarehouse(request, Actor.current());
    }

    @GetMapping("/items")
    @PreAuthorize("hasAuthority('PERM_INVENTORY_VIEW')")
    public PageResponse<ItemView> items(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return setup.items(q, page, size);
    }

    @GetMapping("/items/{id}")
    @PreAuthorize("hasAuthority('PERM_INVENTORY_VIEW')")
    public ItemView item(@PathVariable UUID id) {
        return setup.item(id);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_INVENTORY_MANAGE')")
    public ItemView createItem(@Valid @RequestBody ItemRequest request) {
        return setup.createItem(request, Actor.current());
    }

    @GetMapping("/items/{id}/movements")
    @PreAuthorize("hasAuthority('PERM_INVENTORY_VIEW')")
    public PageResponse<MovementView> movements(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return reports.movements(id, page, size);
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('PERM_INVENTORY_VIEW')")
    public PageResponse<DocumentSummary> documents(@RequestParam(required = false) String kind,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return documents.list(kind, status, page, size);
    }

    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('PERM_INVENTORY_VIEW')")
    public DocumentView document(@PathVariable UUID id) {
        return documents.get(id);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_INVENTORY_MANAGE')")
    @Operation(summary = "Prepare a goods receipt, issue, stock count, transfer or price adjustment (draft)")
    public DocumentView createDocument(@Valid @RequestBody DocumentRequest request) {
        return documents.create(request);
    }

    @PostMapping(value = "/documents/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_INVENTORY_MANAGE')")
    @Operation(summary = "Create a draft document from a CSV of lines (all rows validated; nothing created on any error)")
    public DocumentView importDocument(@RequestPart("file") MultipartFile file, @RequestParam String kind,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate documentDate, @RequestParam String description,
            @RequestParam(required = false) UUID supplierId, @RequestParam(required = false) UUID costCenterId,
            @RequestParam(required = false) String reference) throws IOException {
        if (file.getSize() > 2_000_000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The file is larger than 2 MB.");
        }
        return documents.importLines(kind.trim().toUpperCase(), documentDate, supplierId, reference, costCenterId, description,
                file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping("/documents/{id}/{action:post|cancel}")
    @PreAuthorize("hasAnyAuthority('PERM_INVENTORY_MANAGE', 'PERM_JOURNAL_POST')")
    @Operation(summary = "Post (moves stock, re-prices the average and records the ledger entry) or cancel a draft")
    public DocumentView documentAction(@PathVariable UUID id, @PathVariable String action, @Valid @RequestBody VersionRequest request) {
        return "post".equals(action) ? documents.post(id, request.version()) : documents.cancel(id, request.version());
    }

    @GetMapping("/gr-ir")
    @PreAuthorize("hasAnyAuthority('PERM_INVENTORY_VIEW', 'PERM_AP_VIEW')")
    public GrIrStatus grIr() {
        return reports.grIr();
    }

    @PostMapping("/gr-ir/matches")
    @PreAuthorize("hasAuthority('PERM_AP_INVOICE_PREPARE')")
    public GrIrStatus match(@Valid @RequestBody MatchRequest request) {
        return reports.match(request);
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("hasAuthority('PERM_INVENTORY_VIEW') and hasAuthority('PERM_LEDGER_VIEW')")
    public InventoryReconciliation reconciliation() {
        return reports.reconciliation();
    }
}
