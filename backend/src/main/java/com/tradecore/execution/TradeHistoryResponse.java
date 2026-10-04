package com.tradecore.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TradeHistoryResponse(UUID executionId, UUID orderId, String exchange, String symbol,
        String side, String tradingMode, long executedQuantity, BigDecimal executionPrice, Instant executedAt) {
    public static TradeHistoryResponse from(Execution execution) {
        return new TradeHistoryResponse(execution.getId(), execution.getOrder().getId(),
                execution.getInstrument().getExchange(), execution.getInstrument().getSymbol(),
                execution.getSide(), execution.getTradingMode(), execution.getQuantity(),
                execution.getPrice(), execution.getExecutedAt());
    }
}
