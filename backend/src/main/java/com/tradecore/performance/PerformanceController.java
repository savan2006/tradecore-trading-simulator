package com.tradecore.performance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Performance")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/performance")
public class PerformanceController {
    private final PerformanceQueryService service;

    public PerformanceController(PerformanceQueryService service) {
        this.service = service;
    }

    @Operation(summary = "Me", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping("/me")
    public PerformanceResponse me(Authentication authentication) {
        return service.forUser(authentication.getName());
    }
}
