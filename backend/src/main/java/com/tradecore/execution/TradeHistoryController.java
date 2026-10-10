package com.tradecore.execution;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Trades")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/trades")
public class TradeHistoryController {
    private final com.tradecore.order.OrderHistoryQueryService queryService;

    public TradeHistoryController(com.tradecore.order.OrderHistoryQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "Trades", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping
    public TradeHistoryPageResponse trades(Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.trades(authentication.getName(), page, size);
    }
}
