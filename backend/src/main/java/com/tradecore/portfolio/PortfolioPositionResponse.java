package com.tradecore.portfolio;

import java.math.BigDecimal;

public record PortfolioPositionResponse(
        String exchange,
        String symbol,
        String tradingMode,
        long quantity,
        long reservedQuantity,
        long sellableQuantity,
        BigDecimal averageCost,
        BigDecimal currentPrice,
        BigDecimal marketValue,
        BigDecimal unrealizedPnl,
        BigDecimal realizedPnl,
        String valuationStatus) { }
