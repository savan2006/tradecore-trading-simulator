package com.tradecore.market;

/** Failure after earlier provider windows committed; the progress remains resumable and countable. */
final class HistoricalBackfillPartialFailure extends RuntimeException {
    private final MarketDataIngestionResult persisted;
    private final RuntimeException failure;

    HistoricalBackfillPartialFailure(RuntimeException failure, MarketDataIngestionResult persisted) {
        super(failure.getMessage(), failure);
        this.failure = failure;
        this.persisted = persisted;
    }

    MarketDataIngestionResult persisted() { return persisted; }
    RuntimeException failure() { return failure; }
}
