package com.tradecore.performance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PerformanceResponse(
        long totalOrders,
        long filledOrders,
        long cancelledOrders,
        long totalExecutions,
        int currentOpenPositions,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal totalPnl,
        BigDecimal currentPortfolioValue,
        String valuationStatus,
        long buyOrders,
        long sellOrders,
        long deliveryOrders,
        long intradayOrders,
        long profitableClosedPositions,
        long losingClosedPositions,
        ClosedPositionPerformance bestRealizedPosition,
        ClosedPositionPerformance worstRealizedPosition,
        List<PerformancePoint> recentPerformance) { }

record ClosedPositionPerformance(String symbol, String tradingMode, BigDecimal realizedPnl) { }

record PerformancePoint(String symbol, String tradingMode, Instant closedAt, BigDecimal realizedPnl) { }
