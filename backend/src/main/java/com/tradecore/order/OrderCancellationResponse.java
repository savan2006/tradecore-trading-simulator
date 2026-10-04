package com.tradecore.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderCancellationResponse(
        UUID orderId,
        String status,
        BigDecimal releasedFunds,
        long releasedSellQuantity,
        Instant updatedAt) {
    static OrderCancellationResponse cancelled(TradingOrder order, BigDecimal funds, long quantity) {
        return new OrderCancellationResponse(order.getId(), order.getStatus(), funds, quantity, order.getUpdatedAt());
    }
}
