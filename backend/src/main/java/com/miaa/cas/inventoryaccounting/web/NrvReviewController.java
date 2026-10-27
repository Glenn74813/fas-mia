package com.miaa.cas.inventoryaccounting.web;

import com.miaa.cas.inventoryaccounting.NrvReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory/nrv-reviews")
@Tag(name = "Inventory", description = "Lower of cost and net realisable value review (TOR Annex B.4)")
public class NrvReviewController {

    public record CreateRequest(@NotNull LocalDate asOf) {
    }

    public record PriceRequest(@NotBlank @Size(max = 40) String itemCode,
            @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal marketUnitValue, @Size(max = 200) String source) {
    }

    public record PricesRequest(long version, @NotNull @Size(max = 5000) List<@Valid @NotNull PriceRequest> prices) {
    }

    public record ProposeRequest(long version, @Size(max = 1000) String note) {
    }

    public record VersionRequest(long version) {
    }

    private final NrvReviewService reviews;

    public NrvReviewController(NrvReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_INVENTORY_VIEW', 'PERM_INVENTORY_MANAGE')")
    public List<NrvReviewService.Review> list() {
        return reviews.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_INVENTORY_VIEW', 'PERM_INVENTORY_MANAGE')")
    public NrvReviewService.Review get(@PathVariable UUID id) {
        return reviews.get(id, true);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_INVENTORY_MANAGE')")
    @Operation(summary = "Start a review: snapshot quantity, cost, movement rate and coverage of every item with stock on the date")
    public NrvReviewService.Review create(@Valid @RequestBody CreateRequest r) {
        return reviews.create(r.asOf());
    }

    @PutMapping("/{id}/prices")
    @PreAuthorize("hasAuthority('PERM_INVENTORY_MANAGE')")
    @Operation(summary = "Enter or import market values per unit, by item code")
    public NrvReviewService.Review prices(@PathVariable UUID id, @Valid @RequestBody PricesRequest r) {
        return reviews.setPrices(id, r.version(), r.prices().stream()
                .map(p -> new NrvReviewService.Price(p.itemCode(), p.marketUnitValue(), p.source())).toList());
    }

    @PostMapping("/{id}/propose")
    @PreAuthorize("hasAuthority('PERM_INVENTORY_MANAGE')")
    @Operation(summary = "Create the draft revaluation writing items down to market value where it is below cost")
    public NrvReviewService.Review propose(@PathVariable UUID id, @Valid @RequestBody ProposeRequest r) {
        return reviews.propose(id, r.version(), r.note());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_INVENTORY_MANAGE')")
    public NrvReviewService.Review cancel(@PathVariable UUID id, @RequestBody VersionRequest r) {
        return reviews.cancel(id, r.version());
    }
}
