package com.tradecore.market;

import com.tradecore.market.HistoricalBackfillStatus.InstrumentBackfillFailure;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.tradecore.admin.JobRunTracker;

/** One serial, resumable-at-candle-granularity background job. */
@Service
public class HistoricalBackfillService {
    private static final Logger log = LoggerFactory.getLogger(HistoricalBackfillService.class);
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int MAX_MONTHS = 36;
    private static final int MAX_BATCH_SIZE = 10;

    private final MarketDataIngestionService ingestion;
    private final HistoricalBackfillProperties properties;
    private final ThreadPoolTaskExecutor executor;
    private final JobRunTracker tracker;
    private volatile Instant trackedStart;
    private volatile HistoricalBackfillStatus status = idle();

    @Autowired
    public HistoricalBackfillService(MarketDataIngestionService ingestion,
            HistoricalBackfillProperties properties,
            @Qualifier("historicalBackfillExecutor") ThreadPoolTaskExecutor executor, JobRunTracker tracker) {
        this.ingestion = ingestion;
        this.properties = properties;
        this.executor = executor;
        this.tracker = tracker;
    }

    public HistoricalBackfillService(MarketDataIngestionService ingestion, HistoricalBackfillProperties properties,
            ThreadPoolTaskExecutor executor) { this(ingestion, properties, executor, new JobRunTracker()); }

    public synchronized HistoricalBackfillStatus start(HistoricalBackfillRequest request) {
        if (isActive(status.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A historical backfill job is already active");
        }
        int months = request == null || request.months() == null ? properties.getDefaultMonths() : request.months();
        validateMonths(months);
        List<String> requestedSymbols = normalizeSymbols(request == null ? null : request.symbols());
        List<Instrument> selected;
        try {
            selected = ingestion.resolveHistoricalBackfillInstruments(requestedSymbols);
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requested instruments could not be resolved");
        } catch (MarketDataProviderException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Market data provider could not resolve instruments");
        }
        int batchSize = properties.getInstrumentBatchSize();
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException("tradecore.market-data.backfill.instrument-batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        LocalDate throughDate = LocalDate.now(EXCHANGE_ZONE).minusDays(1);
        LocalDate fromDate = throughDate.minusMonths(months);
        Instant now = Instant.now();
        HistoricalBackfillStatus queued = new HistoricalBackfillStatus(UUID.randomUUID(), "QUEUED", months,
                fromDate, throughDate, batchSize, batchCount(selected.size(), batchSize), 0, selected.size(),
                0, 0, 0, 0, 0, 0, null, now, now, List.of());
        status = queued;
        trackedStart = tracker.start("HISTORICAL_BACKFILL");
        try {
            executor.execute(() -> run(queued, selected));
        } catch (RejectedExecutionException failure) {
            tracker.finish("HISTORICAL_BACKFILL", trackedStart, "FAILURE", 0, 0, 0, 1, failure.getMessage());
            status = withState(queued, "FAILED", Instant.now());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Historical backfill executor is busy");
        }
        return status;
    }

    public HistoricalBackfillStatus status() {
        return status;
    }

    private void run(HistoricalBackfillStatus queued, List<Instrument> instruments) {
        synchronized (this) {
            if (!queued.jobId().equals(status.jobId())) return;
            status = withState(status, "RUNNING", Instant.now());
        }
        try {
            List<InstrumentBackfillFailure> failures = new ArrayList<>();
            int processed = 0, succeeded = 0, failed = 0, received = 0, inserted = 0, skipped = 0;
            int batchSize = queued.batchSize();
            for (int offset = 0; offset < instruments.size(); offset += batchSize) {
                int batch = offset / batchSize + 1;
                int end = Math.min(offset + batchSize, instruments.size());
                for (int index = offset; index < end; index++) {
                    Instrument instrument = instruments.get(index);
                    updateProgress(queued.jobId(), "RUNNING", batch, instrument.getSymbol(), processed,
                            succeeded, failed, received, inserted, skipped, failures);
                    try {
                        MarketDataIngestionResult result = ingestion.backfillDailyCandles(
                                instrument, queued.months(), queued.throughDate());
                        received += result.received();
                        inserted += result.inserted();
                        skipped += result.skipped();
                        succeeded++;
                    } catch (RuntimeException failureCause) {
                        if (failureCause instanceof HistoricalBackfillPartialFailure partial) {
                            received += partial.persisted().received();
                            inserted += partial.persisted().inserted();
                            skipped += partial.persisted().skipped();
                            failureCause = partial.failure();
                        }
                        failed++;
                        String message = boundedMessage(failureCause);
                        failures.add(new InstrumentBackfillFailure(instrument.getSymbol(),
                                category(failureCause), message));
                        log.warn("Historical candle backfill failed for {} category={}: {}",
                                instrument.getSymbol(), category(failureCause), message);
                    } finally {
                        processed++;
                        updateProgress(queued.jobId(), "RUNNING", batch, null, processed,
                                succeeded, failed, received, inserted, skipped, failures);
                    }
                }
            }
            synchronized (this) {
                if (queued.jobId().equals(status.jobId())) {
                    status = snapshot(queued, failed == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS",
                            queued.totalBatches(), null, processed, succeeded, failed, received, inserted, skipped,
                            Instant.now(), failures);
                    tracker.finish("HISTORICAL_BACKFILL", trackedStart, failed == 0 ? "SUCCESS" : "FAILURE",
                            processed, inserted, skipped, failed, failures.isEmpty() ? null : failures.get(0).message());
                }
            }
        } catch (RuntimeException failure) {
            log.error("Historical backfill job {} failed category={}", queued.jobId(), category(failure));
            synchronized (this) {
                if (queued.jobId().equals(status.jobId())) {
                    status = withState(status, "FAILED", Instant.now());
                    tracker.finish("HISTORICAL_BACKFILL", trackedStart, "FAILURE", status.processedInstruments(),
                            status.candlesInserted(), status.candlesSkipped(), status.failedInstruments() + 1, failure.getMessage());
                }
            }
        }
    }

    private synchronized void updateProgress(UUID jobId, String state, int batch, String current,
            int processed, int succeeded, int failed, int received, int inserted, int skipped,
            List<InstrumentBackfillFailure> failures) {
        if (jobId.equals(status.jobId())) {
            status = snapshot(status, state, batch, current, processed, succeeded, failed,
                    received, inserted, skipped, Instant.now(), failures);
        }
    }

    private static List<String> normalizeSymbols(List<String> symbols) {
        if (symbols == null) return null;
        if (symbols.isEmpty() || symbols.size() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbols must contain 1 to 80 approved NSE symbols");
        }
        List<String> normalized = symbols.stream().map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT)).toList();
        if (normalized.stream().anyMatch(String::isBlank) || normalized.stream().distinct().count() != normalized.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbols must be non-blank and unique");
        }
        return normalized;
    }

    private void validateMonths(int months) {
        if (months < 1 || months > MAX_MONTHS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "months must be between 1 and " + MAX_MONTHS);
        }
        if (properties.getDefaultMonths() < 1 || properties.getDefaultMonths() > MAX_MONTHS) {
            throw new IllegalStateException("tradecore.market-data.backfill.default-months must be between 1 and " + MAX_MONTHS);
        }
    }

    private static HistoricalBackfillStatus snapshot(HistoricalBackfillStatus source, String state,
            int batch, String current, int processed, int succeeded, int failed, int received,
            int inserted, int skipped, Instant updated, List<InstrumentBackfillFailure> failures) {
        return new HistoricalBackfillStatus(source.jobId(), state, source.months(), source.fromDate(),
                source.throughDate(), source.batchSize(), source.totalBatches(), batch, source.totalInstruments(),
                processed, succeeded, failed, received, inserted, skipped, current,
                source.startedAt(), updated, List.copyOf(failures));
    }

    private static HistoricalBackfillStatus withState(HistoricalBackfillStatus source, String state, Instant updated) {
        return new HistoricalBackfillStatus(source.jobId(), state, source.months(), source.fromDate(),
                source.throughDate(), source.batchSize(), source.totalBatches(), source.currentBatch(),
                source.totalInstruments(), source.processedInstruments(), source.successfulInstruments(),
                source.failedInstruments(), source.candlesReceived(), source.candlesInserted(),
                source.candlesSkipped(), source.currentSymbol(), source.startedAt(), updated, source.failures());
    }

    private static HistoricalBackfillStatus idle() {
        Instant now = Instant.now();
        return new HistoricalBackfillStatus(null, "IDLE", 0, null, null, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, null, null, now, List.of());
    }

    private static int batchCount(int instruments, int batchSize) {
        return instruments == 0 ? 0 : (instruments + batchSize - 1) / batchSize;
    }

    private static boolean isActive(String state) {
        return "QUEUED".equals(state) || "RUNNING".equals(state);
    }

    private static String category(RuntimeException failure) {
        if (failure instanceof HistoricalBackfillPartialFailure partial) failure = partial.failure();
        if (failure instanceof MarketDataProviderException providerFailure) return providerFailure.category().name();
        if (failure instanceof org.springframework.dao.DataAccessException) return "PERSISTENCE";
        return "APPLICATION";
    }

    private static String boundedMessage(RuntimeException failure) {
        if (failure instanceof HistoricalBackfillPartialFailure partial) failure = partial.failure();
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return "Provider or persistence operation failed";
        message = message.replaceAll("(?i)(password|secret|token|api[-_ ]?key|authorization)\\s*[:=]\\s*[^ ,;]+", "$1=[REDACTED]")
                .replaceAll("https?://\\S+", "[URL]").replaceAll("[\\r\\n\\t]", " ").trim();
        return message.length() <= 240 ? message : message.substring(0, 240);
    }
}
