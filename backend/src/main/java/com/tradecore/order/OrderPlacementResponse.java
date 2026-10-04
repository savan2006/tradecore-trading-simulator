package com.tradecore.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderPlacementResponse(
        UUID orderId,
        UUID accountId,
        UUID instrumentId,
        String symbol,
        String exchange,
        String side,
        String orderType,
        String tradingMode,
        long requestedQuantity,
        long executedQuantity,
        long remainingQuantity,
        BigDecimal limitPrice,
        String status,
        Instant createdAt) {

    static OrderPlacementResponse from(TradingOrder order) {
        return new OrderPlacementResponse(order.getId(), order.getAccount().getId(),
                order.getInstrument().getId(), order.getInstrument().getSymbol(),
                order.getInstrument().getExchange(), order.getSide(), order.getOrderType(),
                order.getTradingMode(), order.getRequestedQuantity(), order.getExecutedQuantity(),
                order.getRemainingQuantity(), order.getLimitPrice(), order.getStatus(), order.getCreatedAt());
    }
}
