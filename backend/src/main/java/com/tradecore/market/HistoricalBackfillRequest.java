package com.tradecore.market;

import java.util.List;

/** Optional instrument subset and period for an administrator-triggered historical backfill. */
public record HistoricalBackfillRequest(Integer months, List<String> symbols) {
}
