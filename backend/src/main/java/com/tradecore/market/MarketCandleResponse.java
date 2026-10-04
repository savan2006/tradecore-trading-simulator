package com.tradecore.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Provider-neutral REST representation of one persisted daily candle. */
public record MarketCandleResponse(
        LocalDate tradingDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        Instant persistedAt) {

    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");

    static MarketCandleResponse from(MarketCandle candle) {
        return new MarketCandleResponse(
                candle.getBucketStart().atZone(EXCHANGE_ZONE).toLocalDate(),
                candle.getOpenPrice(),
                candle.getHighPrice(),
                candle.getLowPrice(),
                candle.getClosePrice(),
                candle.getVolume(),
                candle.getCreatedAt());
    }
}
