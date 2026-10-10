package com.tradecore.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "Admin")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/admin/market-data/completeness")
public class AdminMarketDataCompletenessController {
    private final MarketDataCompletenessService service;

    public AdminMarketDataCompletenessController(MarketDataCompletenessService service) {
        this.service = service;
    }

    @Operation(summary = "Report", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required")})

    @GetMapping
    public MarketDataCompletenessReport report(@RequestParam(defaultValue = "12") int months) {
        if (months < 1 || months > 36) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "months must be between 1 and 36");
        }
        return service.report(months);
    }
}
