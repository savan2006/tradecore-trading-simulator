package com.tradecore.market;

import java.math.BigDecimal;
import java.time.Instant;

/** Provider-neutral REST view of the latest persisted quote for one instrument. */
public record MarketQuoteResponse(
        String symbol,
        String exchange,
        BigDecimal lastPrice,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal previousClose,
        Long volume,
        Instant marketTimestamp,
        Instant providerUpdatedTimestamp,
        Instant receivedTimestamp,
        String dataStatus,
        Long freshnessAgeSeconds) {

    public static MarketQuoteResponse unavailable(Instrument instrument) {
        return new MarketQuoteResponse(instrument.getSymbol(), instrument.getExchange(),
                null, null, null, null, null, null, null, null, null, "UNAVAILABLE", null);
    }

    public static MarketQuoteResponse from(MarketQuote quote, Instant now) {
        Instrument instrument = quote.getInstrument();
        return fromPersisted(instrument.getSymbol(), instrument.getExchange(), quote.getLastPrice(),
                quote.getOpenPrice(), quote.getHighPrice(), quote.getLowPrice(), quote.getPreviousClose(),
                quote.getVolume(), quote.getMarketAt(), quote.getProviderUpdatedAt(), quote.getReceivedAt(),
                quote.getDataStatus(), now);
    }

    static MarketQuoteResponse fromCache(CachedMarketQuote quote, Instant now) {
        return fromPersisted(quote.symbol(), quote.exchange(), quote.lastPrice(), quote.open(), quote.high(),
                quote.low(), quote.previousClose(), quote.volume(), quote.marketTimestamp(),
                quote.providerUpdatedTimestamp(), quote.receivedTimestamp(), quote.persistedDataStatus(), now);
    }

    private static MarketQuoteResponse fromPersisted(String symbol, String exchange, BigDecimal lastPrice,
            BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal previousClose, Long volume,
            Instant marketTimestamp, Instant providerUpdatedAt, Instant receivedAt,
            String persistedDataStatus, Instant now) {
        String status = freshnessStatus(persistedDataStatus, providerUpdatedAt, receivedAt, now);
        Long ageSeconds = providerUpdatedAt == null
                ? null
                : Math.max(0, java.time.Duration.between(providerUpdatedAt, now).getSeconds());
        return new MarketQuoteResponse(
                symbol,
                exchange,
                lastPrice,
                open,
                high,
                low,
                previousClose,
                volume,
                marketTimestamp,
                providerUpdatedAt,
                receivedAt,
                status,
                ageSeconds);
    }

    private static String freshnessStatus(String persistedDataStatus, Instant providerUpdatedAt,
            Instant receivedAt, Instant now) {
        if ("UNAVAILABLE".equals(persistedDataStatus)) {
            return "UNAVAILABLE";
        }
        // CM Market reports a five-minute crawl interval; allow two intervals for API status.
        if (!"LIVE".equals(persistedDataStatus) || providerUpdatedAt == null
                || receivedAt == null
                || providerUpdatedAt.isBefore(now.minusSeconds(600))
                || providerUpdatedAt.isAfter(now)) {
            return "STALE";
        }
        return "LIVE";
    }
}
