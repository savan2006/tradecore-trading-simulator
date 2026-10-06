package com.tradecore.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Small in-memory progress snapshot; raw provider payloads and candle rows are never retained here. */
public record HistoricalBackfillStatus(
        UUID jobId,
        String state,
        int months,
        LocalDate fromDate,
        LocalDate throughDate,
        int batchSize,
        int totalBatches,
        int currentBatch,
        int totalInstruments,
        int processedInstruments,
        int successfulInstruments,
        int failedInstruments,
        int candlesReceived,
        int candlesInserted,
        int candlesSkipped,
        String currentSymbol,
        Instant startedAt,
        Instant updatedAt,
        List<InstrumentBackfillFailure> failures) {

    public record InstrumentBackfillFailure(String symbol, String category, String message) {}
}
