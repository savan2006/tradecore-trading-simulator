package com.tradecore.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A user's simulated order state and executed and remaining quantities.")
public record OrderHistoryResponse(UUID orderId, String exchange, String symbol, String side,
        String orderType, String tradingMode, long requestedQuantity, long executedQuantity,
        long remainingQuantity, BigDecimal limitPrice, BigDecimal triggerPrice,
        String status, Instant createdAt, Instant updatedAt) {
    static OrderHistoryResponse from(TradingOrder order) {
        return new OrderHistoryResponse(order.getId(), order.getInstrument().getExchange(),
                order.getInstrument().getSymbol(), order.getSide(), order.getOrderType(), order.getTradingMode(),
                order.getRequestedQuantity(), order.getExecutedQuantity(), order.getRemainingQuantity(),
                order.getLimitPrice(), order.getTriggerPrice(), order.getStatus(),
                order.getCreatedAt(), order.getUpdatedAt());
    }
}
