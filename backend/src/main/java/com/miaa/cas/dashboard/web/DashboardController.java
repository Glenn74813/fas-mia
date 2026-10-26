package com.miaa.cas.dashboard.web;

import com.miaa.cas.dashboard.DashboardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's dashboard; each section is present only with the permission behind it. */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated() and !hasAuthority('PERM_PORTAL_SELF_VIEW')")
    public DashboardService.Dashboard mine() {
        return dashboard.mine();
    }
}
