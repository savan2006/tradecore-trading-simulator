package com.tradecore.strategylab;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Strategy Lab")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/strategy-lab")
public class StrategyLabController {

    private final StrategyBacktestService service;

    public StrategyLabController(StrategyBacktestService service) {
        this.service = service;
    }

    @Operation(summary = "Run Backtest", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state"), @ApiResponse(responseCode = "422", description = "The request cannot be processed under current validation rules")})

    @PostMapping("/backtests")
    public BacktestResponse runBacktest(@Valid @RequestBody BacktestRequest request) {
        return service.run(request);
    }
}
