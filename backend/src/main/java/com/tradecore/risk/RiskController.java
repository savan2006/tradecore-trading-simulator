package com.tradecore.risk;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Risk")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {
    private final RiskQueryService queryService;

    public RiskController(RiskQueryService queryService) { this.queryService = queryService; }

    @Operation(summary = "Current Limits", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping("/me")
    public List<RiskLimitResponse> currentLimits(Authentication authentication) {
        return queryService.currentLimits(authentication.getName());
    }
}
