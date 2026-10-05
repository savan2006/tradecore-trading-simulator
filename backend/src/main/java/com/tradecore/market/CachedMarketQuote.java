package com.tradecore.market;

import java.math.BigDecimal;
import java.time.Instant;

/** Stable persisted quote fields cached in Redis; freshness is recalculated on every cache hit. */
record CachedMarketQuote(String symbol, String exchange, BigDecimal lastPrice, BigDecimal open,
        BigDecimal high, BigDecimal low, BigDecimal previousClose, Long volume,
        Instant marketTimestamp, Instant providerUpdatedTimestamp, Instant receivedTimestamp,
        String persistedDataStatus) {
    static CachedMarketQuote from(MarketQuote quote) {
        Instrument instrument = quote.getInstrument();
        return new CachedMarketQuote(instrument.getSymbol(), instrument.getExchange(), quote.getLastPrice(),
                quote.getOpenPrice(), quote.getHighPrice(), quote.getLowPrice(), quote.getPreviousClose(),
                quote.getVolume(), quote.getMarketAt(), quote.getProviderUpdatedAt(), quote.getReceivedAt(),
                quote.getDataStatus());
    }

    MarketQuoteResponse toResponse(Instant now) {
        return MarketQuoteResponse.fromCache(this, now);
    }
}
