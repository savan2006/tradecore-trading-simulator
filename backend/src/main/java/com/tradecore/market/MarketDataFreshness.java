package com.tradecore.market;

import java.time.Duration;
import java.time.Instant;

/** Provider-reported refresh information, kept separate from the exchange trade timestamp. */
public record MarketDataFreshness(
        String dataSource,
        boolean available,
        Instant dataUpdatedAt,
        Duration expectedRefreshInterval) {
}
