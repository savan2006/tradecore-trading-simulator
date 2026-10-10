package com.tradecore.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Process-local snapshots of the most recent scheduled and background job runs. */
@Component
public class JobRunTracker {
    private static final List<String> JOBS = List.of("QUOTE_REFRESH", "DAILY_CANDLE_REFRESH", "ORDER_EXECUTION_SWEEP",
            "INTRADAY_SQUARE_OFF", "PRICE_ALERT_PROCESSING", "HISTORICAL_BACKFILL");
    private final Map<String, JobRunStatus> statuses = new ConcurrentHashMap<>();
    public JobRunTracker() { JOBS.forEach(job -> statuses.put(job,
            new JobRunStatus(job, null, null, null, "NOT_RUN", 0, 0, 0, 0, null))); }
    public synchronized Instant start(String job) {
        Instant started = Instant.now();
        statuses.put(job, new JobRunStatus(job, started, null, null, "RUNNING", 0, 0, 0, 0, null));
        return started;
    }
    public synchronized void finish(String job, Instant started, String outcome, long processed, long updated,
            long skipped, long failed, String error) {
        JobRunStatus current = statuses.get(job);
        if (current == null || !started.equals(current.startedAt())) return;
        Instant finished = Instant.now();
        statuses.put(job, new JobRunStatus(job, started, finished,
                Duration.between(started, finished).toMillis(), outcome, processed, updated, skipped, failed,
                sanitize(error)));
    }
    public synchronized void skipped(String job) {
        Instant now = Instant.now();
        statuses.put(job, new JobRunStatus(job, now, now, 0L, "SKIPPED", 0, 0, 0, 0, null));
    }
    public List<JobRunStatus> snapshot() { return JOBS.stream().map(statuses::get).toList(); }
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return null;
        String safe = value.replaceAll("(?i)(password|secret|token|api[-_ ]?key|authorization)\\s*[:=]\\s*[^ ,;]+", "$1=[REDACTED]")
                .replaceAll("https?://\\S+", "[URL]").replaceAll("[\\r\\n\\t]", " ").trim();
        return safe.length() > 240 ? safe.substring(0, 240) : safe;
    }
}
