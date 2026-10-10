package com.tradecore.learning;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Learning")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/learning/companies")
public class LearningProfileController {
    private final LearningProfileService service;
    private final LearningCompanyOverviewService overviewService;

    public LearningProfileController(LearningProfileService service, LearningCompanyOverviewService overviewService) {
        this.service = service;
        this.overviewService = overviewService;
    }

    @Operation(summary = "List", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping
    public List<LearningProfileResponse> list() { return service.list(); }

    @Operation(summary = "Get", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found")})

    @GetMapping("/{symbol}")
    public LearningProfileResponse get(@PathVariable String symbol) { return service.get(symbol); }

    @Operation(summary = "Overview", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found")})

    @GetMapping("/{symbol}/overview")
    public LearningCompanyOverviewResponse overview(@PathVariable String symbol,
            @RequestParam(required = false) Integer limit) {
        return overviewService.overview(symbol, limit);
    }
}
