package com.notify.admin.controller;

import com.notify.admin.dto.AdminSystemStatusResponse;
import com.notify.admin.security.AdminSecurityUtils;
import com.notify.admin.service.AdminSystemStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin component status endpoint (/ ). Strict admin boundary.
 */
@RestController
@RequestMapping("/api/v1/admin/system")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminSystemStatusController {

    private final AdminSystemStatusService adminSystemStatusService;

    public AdminSystemStatusController(AdminSystemStatusService adminSystemStatusService) {
        this.adminSystemStatusService = adminSystemStatusService;
    }

    @GetMapping("/status")
    public ResponseEntity<AdminSystemStatusResponse> status(Authentication authentication) {
        AdminSecurityUtils.requireAdminPrincipal(authentication);
        return ResponseEntity.ok(adminSystemStatusService.status());
    }
}
