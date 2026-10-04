package com.tradecore.market;

/** Counts written by one transactional market-data ingestion operation. */
public record MarketDataIngestionResult(
        int received,
        int inserted,
        int updated,
        int skipped,
        int stale) {
}
