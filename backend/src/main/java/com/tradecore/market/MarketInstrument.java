package com.tradecore.market;

/** Provider-neutral instrument match returned by symbol discovery. */
public record MarketInstrument(String exchange, String symbol, String providerInstrumentId) {
}
