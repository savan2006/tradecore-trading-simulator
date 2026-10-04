package com.tradecore.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Provider-neutral OHLCV candle. Daily provider data has a date but no fabricated intraday timestamp. */
public record MarketCandleSnapshot(
        String exchange,
        String symbol,
        String providerInstrumentId,
        LocalDate tradingDate,
        Instant tradingTimestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Long volume,
        BigDecimal lastPrice,
        String dataSource,
        Instant dataUpdatedAt) {
}
