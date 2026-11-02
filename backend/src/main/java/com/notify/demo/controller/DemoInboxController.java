package com.notify.demo.controller;

import com.notify.demo.dto.DemoInboxItemResponse;
import com.notify.demo.service.DemoInboxService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public demo inbox (/ , ). Permit-listed in {@code SecurityConfig}; returns 404 when the demo
 * surface is disabled. Exposes only masked recipients and sanitized subjects.
 */
@RestController
@RequestMapping("/api/v1/demo/inbox")
public class DemoInboxController {

    private final DemoInboxService demoInboxService;

    public DemoInboxController(DemoInboxService demoInboxService) {
        this.demoInboxService = demoInboxService;
    }

    @GetMapping
    public ResponseEntity<List<DemoInboxItemResponse>> inbox() {
        return ResponseEntity.ok(demoInboxService.inbox());
    }
}
