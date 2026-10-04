package com.tradecore.execution;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trades")
public class TradeHistoryController {
    private final com.tradecore.order.OrderHistoryQueryService queryService;

    public TradeHistoryController(com.tradecore.order.OrderHistoryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public TradeHistoryPageResponse trades(Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.trades(authentication.getName(), page, size);
    }
}
