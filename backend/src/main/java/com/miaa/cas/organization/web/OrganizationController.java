package com.miaa.cas.organization.web;

import com.miaa.cas.organization.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Organisations, offices, departments and users' office assignments (FR-ORG-01). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Organisation")
public class OrganizationController {

    public record OrganizationUpdate(@NotNull Long version, @NotBlank @Size(max = 200) String name, @Size(max = 20) String tin,
            @NotBlank String status) {
    }

    public record UnitCreate(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 200) String name, UUID costCenterId) {
    }

    public record UnitUpdate(@NotNull Long version, @NotBlank @Size(max = 200) String name, @NotBlank String status, UUID costCenterId) {
    }

    public record AssignRequest(@NotNull UUID officeId, UUID departmentId, @NotNull LocalDate effectiveFrom) {
    }

    public record EndRequest(@NotNull LocalDate effectiveTo) {
    }

    private final OrganizationService organization;

    public OrganizationController(OrganizationService organization) {
        this.organization = organization;
    }

    @GetMapping("/organizations")
    @PreAuthorize("hasAnyAuthority('PERM_USER_VIEW', 'PERM_ORGANIZATION_ADMINISTER')")
    public List<OrganizationService.Organization> tree() {
        return organization.tree();
    }

    @PutMapping("/organizations/{id}")
    @PreAuthorize("hasAuthority('PERM_ORGANIZATION_ADMINISTER')")
    public List<OrganizationService.Organization> updateOrganization(@PathVariable UUID id, @Valid @RequestBody OrganizationUpdate r) {
        organization.updateOrganization(id, r.version(), r.name(), r.tin(), r.status());
        return organization.tree();
    }

    @PostMapping("/organizations/{id}/offices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ORGANIZATION_ADMINISTER')")
    public Map<String, UUID> createOffice(@PathVariable UUID id, @Valid @RequestBody UnitCreate r) {
        return Map.of("id", organization.createOffice(id, r.code(), r.name()));
    }

    @PutMapping("/offices/{id}")
    @PreAuthorize("hasAuthority('PERM_ORGANIZATION_ADMINISTER')")
    public List<OrganizationService.Organization> updateOffice(@PathVariable UUID id, @Valid @RequestBody UnitUpdate r) {
        organization.updateOffice(id, r.version(), r.name(), r.status());
        return organization.tree();
    }

    @PostMapping("/offices/{id}/departments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ORGANIZATION_ADMINISTER')")
    public Map<String, UUID> createDepartment(@PathVariable UUID id, @Valid @RequestBody UnitCreate r) {
        return Map.of("id", organization.createDepartment(id, r.code(), r.name(), r.costCenterId()));
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize("hasAuthority('PERM_ORGANIZATION_ADMINISTER')")
    public List<OrganizationService.Organization> updateDepartment(@PathVariable UUID id, @Valid @RequestBody UnitUpdate r) {
        organization.updateDepartment(id, r.version(), r.name(), r.costCenterId(), r.status());
        return organization.tree();
    }

    @GetMapping("/users/{id}/office-assignments")
    @PreAuthorize("isAuthenticated()")
    public List<OrganizationService.Assignment> assignments(@PathVariable UUID id) {
        return organization.assignments(id);
    }

    @PostMapping("/users/{id}/office-assignments")
    @PreAuthorize("hasAuthority('PERM_ORGANIZATION_ADMINISTER')")
    @Operation(summary = "Assign the user to an office from a date; the current assignment ends the day before")
    public List<OrganizationService.Assignment> assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest r) {
        return organization.assign(id, r.officeId(), r.departmentId(), r.effectiveFrom());
    }

    @PostMapping("/office-assignments/{id}/end")
    @PreAuthorize("hasAuthority('PERM_ORGANIZATION_ADMINISTER')")
    public List<OrganizationService.Assignment> end(@PathVariable UUID id, @Valid @RequestBody EndRequest r) {
        return organization.end(id, r.effectiveTo());
    }
}
