package com.tradecore.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/market-data/completeness")
public class AdminMarketDataCompletenessController {
    private final MarketDataCompletenessService service;

    public AdminMarketDataCompletenessController(MarketDataCompletenessService service) {
        this.service = service;
    }

    @GetMapping
    public MarketDataCompletenessReport report(@RequestParam(defaultValue = "12") int months) {
        if (months < 1 || months > 36) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "months must be between 1 and 36");
        }
        return service.report(months);
    }
}
