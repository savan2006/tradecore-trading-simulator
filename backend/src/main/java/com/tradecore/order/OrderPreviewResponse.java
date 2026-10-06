package com.tradecore.order;

import java.math.BigDecimal;
import java.util.List;

public record OrderPreviewResponse(
        boolean valid,
        List<String> validationErrors,
        String exchange,
        String symbol,
        String side,
        String orderType,
        String tradingMode,
        long quantity,
        BigDecimal currentEligiblePrice,
        BigDecimal estimatedOrderValue,
        BigDecimal estimatedBuyReservation,
        Long sellableQuantity,
        List<String> applicableRiskLimitFailures,
        String quoteFreshnessStatus,
        Long quoteFreshnessAgeSeconds,
        String marketSessionEligibility,
        String marketSessionStatus) { }
