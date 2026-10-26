package com.miaa.cas.inventoryaccounting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** API contracts for items, warehouses, inventory documents, stock, movements, GR/IR and reconciliation. */
public final class InvDtos {

    private InvDtos() {
    }

    private static final String CODE = "^[A-Za-z0-9][A-Za-z0-9.-]{0,39}$";

    public record WarehouseRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 200) String name) {
    }

    public record WarehouseView(UUID id, String code, String name, String status) {
    }

    public record ItemRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 20) String unitOfMeasure, @NotNull UUID inventoryAccountId, @NotNull UUID consumptionAccountId) {
    }

    public record WarehouseQuantity(UUID warehouseId, String warehouseCode, BigDecimal quantity) {
    }

    public record ItemView(UUID id, String code, String name, String unitOfMeasure, UUID inventoryAccountId,
            String inventoryAccountCode, UUID consumptionAccountId, String consumptionAccountCode, String status,
            BigDecimal quantity, BigDecimal value, BigDecimal averageCost, List<WarehouseQuantity> warehouses) {
    }

    /** {@code warehouseId} is required except on a price adjustment, which restates the item's whole cost pool. */
    public record LineRequest(@NotNull UUID itemId, UUID warehouseId, UUID toWarehouseId,
            @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal quantity,
            @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal unitCost) {
    }

    public record DocumentRequest(@NotNull @Pattern(regexp = "GOODS_RECEIPT|ISSUE|COUNT|TRANSFER|REVALUATION") String kind,
            @NotNull LocalDate documentDate, UUID supplierId, @Size(max = 100) String reference, UUID costCenterId,
            @NotBlank @Size(max = 1000) String description,
            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull LineRequest> lines) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    public record LineView(int lineNo, UUID itemId, String itemCode, String itemName, String unitOfMeasure, UUID warehouseId,
            String warehouseCode, String toWarehouseCode, BigDecimal quantity, BigDecimal unitCost, BigDecimal systemQuantity,
            BigDecimal value) {
    }

    public record DocumentSummary(UUID id, String documentNumber, String kind, LocalDate documentDate, String supplierName,
            String reference, String description, String status, BigDecimal totalValue, String preparedBy, Instant updatedAt,
            long version) {
    }

    public record DocumentView(UUID id, String documentNumber, String kind, LocalDate documentDate, UUID supplierId,
            String supplierName, String reference, UUID costCenterId, String costCenterCode, String description, String status,
            BigDecimal totalValue, String preparedBy, String postedBy, UUID postedEntryId, String postedEntryNumber,
            List<LineView> lines, List<String> availableActions, long version) {
    }

    public record MovementView(Instant createdAt, LocalDate movementDate, String documentNumber, String kind, String movementType,
            String warehouseCode, BigDecimal quantity, BigDecimal value, BigDecimal poolQuantity, BigDecimal poolValue,
            BigDecimal averageAfter) {
    }

    public record GrnOpen(UUID id, String documentNumber, LocalDate documentDate, UUID supplierId, String supplierName,
            BigDecimal value, BigDecimal matched, BigDecimal open) {
    }

    public record InvoiceOpen(UUID id, String invoiceNumber, LocalDate invoiceDate, UUID supplierId, String supplierName,
            BigDecimal grIrAmount, BigDecimal matched, BigDecimal available) {
    }

    public record GrIrStatus(List<GrnOpen> receipts, List<InvoiceOpen> invoices, BigDecimal openReceipts,
            BigDecimal unmatchedInvoices) {
    }

    public record MatchRequest(@NotNull UUID grnId, @NotNull UUID invoiceId,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    public record ReconRow(String accountCode, String accountName, String side, BigDecimal register, BigDecimal generalLedger,
            BigDecimal difference) {
    }

    public record InventoryReconciliation(List<ReconRow> rows, boolean reconciled) {
    }
}
