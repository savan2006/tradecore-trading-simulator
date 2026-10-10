package com.tradecore.portfolio;

import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A simulated long position with reserved and sellable quantities and its valuation status.")
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
