package com.tradecore.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PriceAlertResponse(UUID id, UUID watchlistId, UUID instrumentId, String symbol,
        String exchange, String condition, BigDecimal targetPrice, boolean active,
        Instant createdAt, Instant triggeredAt) {
    public static PriceAlertResponse from(PriceAlert alert) {
        return new PriceAlertResponse(alert.getId(), alert.getWatchlist().getId(), alert.getInstrument().getId(),
                alert.getInstrument().getSymbol(), alert.getInstrument().getExchange(), alert.getCondition(),
                alert.getTargetPrice(), alert.isActive(), alert.getCreatedAt(), alert.getTriggeredAt());
    }
}
