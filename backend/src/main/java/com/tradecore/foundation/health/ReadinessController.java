package com.tradecore.foundation.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "System")
@SecurityRequirement(name = "basicAuth")
@RestController
public class ReadinessController {

    @Operation(summary = "Status", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping("/api/v1/foundation/status")
    public FoundationStatus status() {
        return new FoundationStatus("TradeCore backend is running");
    }

    public record FoundationStatus(String status) {
    }
}
