package com.tradecore.market;

import java.math.BigDecimal;
import java.time.Instant;

/** In-memory normalized quote; timestamps retain provider update and exchange trade time separately. */
public record MarketQuoteSnapshot(
        String exchange,
        String symbol,
        String providerInstrumentId,
        Instant tradingTimestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal previousClose,
        Long volume,
        BigDecimal lastPrice,
        String dataSource,
        Instant dataUpdatedAt) {
}
