package com.tradecore.performance;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/performance")
public class PerformanceController {
    private final PerformanceQueryService service;

    public PerformanceController(PerformanceQueryService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public PerformanceResponse me(Authentication authentication) {
        return service.forUser(authentication.getName());
    }
}
