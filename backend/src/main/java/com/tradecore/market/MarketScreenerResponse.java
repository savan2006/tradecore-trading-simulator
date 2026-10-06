package com.tradecore.market;

import java.math.BigDecimal;

public record MarketScreenerResponse(
        String symbol,
        String companyName,
        String sector,
        String category,
        BigDecimal ltp,
        BigDecimal dailyChangePercent,
        Long volume,
        BigDecimal volatilityPercent,
        BigDecimal fiftyTwoWeekHigh,
        BigDecimal fiftyTwoWeekLow,
        BigDecimal distanceFromFiftyTwoWeekHighPercent,
        BigDecimal distanceFromFiftyTwoWeekLowPercent,
        String freshnessStatus,
        String marketStatus) { }
