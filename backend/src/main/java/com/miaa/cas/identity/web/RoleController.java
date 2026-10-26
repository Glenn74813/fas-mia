package com.miaa.cas.identity.web;

import com.miaa.cas.identity.PermissionCatalog;
import com.miaa.cas.identity.RoleAdminService;
import com.miaa.cas.identity.SodPolicy;
import com.miaa.cas.identity.web.IdentityDtos.CreateRoleRequest;
import com.miaa.cas.identity.web.IdentityDtos.RoleChangeResult;
import com.miaa.cas.identity.web.IdentityDtos.RoleResponse;
import com.miaa.cas.identity.web.IdentityDtos.SetPermissionsRequest;
import com.miaa.cas.identity.web.IdentityDtos.UpdateRoleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/api/v1")
@Tag(name = "Roles and permissions")
public class RoleController {

    private final RoleAdminService roles;
    private final PermissionCatalog catalog;

    public RoleController(RoleAdminService roles, PermissionCatalog catalog) {
        this.roles = roles;
        this.catalog = catalog;
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('PERM_ROLE_VIEW')")
    public List<RoleResponse> list() {
        return roles.list();
    }

    @GetMapping("/roles/{code}")
    @PreAuthorize("hasAuthority('PERM_ROLE_VIEW')")
    public RoleResponse get(@PathVariable String code) {
        return roles.get(code);
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ROLE_ADMINISTER')")
    public RoleChangeResult create(@Valid @RequestBody CreateRoleRequest request) {
        return roles.create(request);
    }

    @PutMapping("/roles/{code}")
    @PreAuthorize("hasAuthority('PERM_ROLE_ADMINISTER')")
    public RoleResponse update(@PathVariable String code, @Valid @RequestBody UpdateRoleRequest request) {
        return roles.update(code, request);
    }

    @PutMapping("/roles/{code}/permissions")
    @PreAuthorize("hasAuthority('PERM_ROLE_ADMINISTER')")
    @Operation(summary = "Replace a role's permissions (SoD-checked against every holder of the role)")
    public RoleChangeResult setPermissions(@PathVariable String code, @Valid @RequestBody SetPermissionsRequest request) {
        return roles.setPermissions(code, request.version(), request.permissionCodes());
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('PERM_ROLE_VIEW')")
    public List<PermissionCatalog.PermissionInfo> permissions() {
        return catalog.all();
    }

    @GetMapping("/sod-rules")
    @PreAuthorize("hasAuthority('PERM_ROLE_VIEW')")
    public List<SodPolicy.Rule> sodRules() {
        return catalog.sodRules();
    }
}
