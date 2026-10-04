package com.tradecore.learning;

import com.tradecore.market.MarketCandleResponse;
import com.tradecore.market.MarketQuoteResponse;
import java.util.List;

public record LearningCompanyOverviewResponse(
        LearningProfileResponse learningProfile,
        MarketQuoteResponse latestQuote,
        List<MarketCandleResponse> recentDailyCandles,
        LearningPriceChange latestQuoteChange,
        LearningPriceChange candlePeriodChange,
        String quoteStatus) { }
