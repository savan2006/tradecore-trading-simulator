package com.tradecore.market;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Market")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/market/session")
public class MarketSessionController {
    private final MarketHoursPolicy policy;

    public MarketSessionController(MarketHoursPolicy policy) { this.policy = policy; }

    @Operation(summary = "Current Session", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping
    public MarketSessionResponse currentSession() { return policy.sessionStatus(Instant.now()); }
}
