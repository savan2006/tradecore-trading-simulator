package com.tradecore.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A virtual account balance, position summary, and quote-based valuation. Redis is not the financial source of truth.")
public record PortfolioResponse(
        UUID accountId,
        String currency,
        BigDecimal availableBalance,
        BigDecimal reservedBalance,
        BigDecimal investedCost,
        BigDecimal currentMarketValue,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal totalPnl,
        int positionCount,
        String valuationStatus,
        Instant valuedAt,
        List<PortfolioPositionResponse> positions) { }
