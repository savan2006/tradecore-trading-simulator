package com.tradecore.portfolio;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Portfolio")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {
    private final PortfolioQueryService portfolioQueryService;

    public PortfolioController(PortfolioQueryService portfolioQueryService) {
        this.portfolioQueryService = portfolioQueryService;
    }

    @Operation(summary = "Current Portfolio", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping("/me")
    public PortfolioResponse currentPortfolio(Authentication authentication) {
        return portfolioQueryService.currentPortfolio(authentication.getName());
    }
}
