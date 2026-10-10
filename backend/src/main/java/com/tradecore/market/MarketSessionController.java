package com.tradecore.market;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market/session")
public class MarketSessionController {
    private final MarketHoursPolicy policy;

    public MarketSessionController(MarketHoursPolicy policy) { this.policy = policy; }

    @GetMapping
    public MarketSessionResponse currentSession() { return policy.sessionStatus(Instant.now()); }
}
