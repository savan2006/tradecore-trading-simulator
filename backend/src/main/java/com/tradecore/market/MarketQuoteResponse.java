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

    static MarketQuoteResponse unavailable(Instrument instrument) {
        return new MarketQuoteResponse(instrument.getSymbol(), instrument.getExchange(),
                null, null, null, null, null, null, null, null, null, "UNAVAILABLE", null);
    }

    static MarketQuoteResponse from(MarketQuote quote, Instant now) {
        Instrument instrument = quote.getInstrument();
        Instant providerUpdatedAt = quote.getProviderUpdatedAt();
        String status = freshnessStatus(quote, providerUpdatedAt, now);
        Long ageSeconds = providerUpdatedAt == null
                ? null
                : Math.max(0, java.time.Duration.between(providerUpdatedAt, now).getSeconds());
        return new MarketQuoteResponse(
                instrument.getSymbol(),
                instrument.getExchange(),
                quote.getLastPrice(),
                quote.getOpenPrice(),
                quote.getHighPrice(),
                quote.getLowPrice(),
                quote.getPreviousClose(),
                quote.getVolume(),
                quote.getMarketAt(),
                providerUpdatedAt,
                quote.getReceivedAt(),
                status,
                ageSeconds);
    }

    private static String freshnessStatus(MarketQuote quote, Instant providerUpdatedAt, Instant now) {
        if ("UNAVAILABLE".equals(quote.getDataStatus())) {
            return "UNAVAILABLE";
        }
        // CM Market reports a five-minute crawl interval; allow two intervals for API status.
        if (!"LIVE".equals(quote.getDataStatus()) || providerUpdatedAt == null
                || quote.getReceivedAt() == null
                || providerUpdatedAt.isBefore(now.minusSeconds(600))
                || providerUpdatedAt.isAfter(now)) {
            return "STALE";
        }
        return "LIVE";
    }
}
