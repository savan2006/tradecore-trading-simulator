package com.tradecore.market;

import java.time.LocalDate;
import java.util.List;

/** Bounded daily history response for one supported instrument. */
public record MarketCandleHistoryResponse(
        String symbol,
        String exchange,
        String resolution,
        LocalDate from,
        LocalDate to,
        int limit,
        List<MarketCandleResponse> candles) {
}
