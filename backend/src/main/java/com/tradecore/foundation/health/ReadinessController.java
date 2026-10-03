package com.tradecore.foundation.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReadinessController {

    @GetMapping("/api/v1/foundation/status")
    public FoundationStatus status() {
        return new FoundationStatus("TradeCore backend is running");
    }

    public record FoundationStatus(String status) {
    }
}
