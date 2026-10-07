package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.aiusage.AiUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// OpenAI spending in the owner's admin area (SecurityConfig: /api/v1/admin/** needs ROLE_ADMIN)
@RestController
@RequestMapping("/api/v1/admin/ai-usage")
public class AiUsageAdminController {

    private final AiUsageService service;

    public AiUsageAdminController(AiUsageService service) {
        this.service = service;
    }

    @GetMapping
    public AiUsageService.Summary summary() {
        return service.summary();
    }
}
