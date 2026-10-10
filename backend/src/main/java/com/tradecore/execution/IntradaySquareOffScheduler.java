package com.tradecore.execution;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.tradecore.admin.JobRunTracker;

/** Rechecks after session close; row locks and order/position states make retries safe. */
@Component
public class IntradaySquareOffScheduler {
    private static final Logger log = LoggerFactory.getLogger(IntradaySquareOffScheduler.class);
    private final IntradaySquareOffService service;
    private final AtomicBoolean running = new AtomicBoolean();
    private final JobRunTracker tracker;
    @Autowired
    public IntradaySquareOffScheduler(IntradaySquareOffService service, JobRunTracker tracker) { this.service = service; this.tracker = tracker; }
    public IntradaySquareOffScheduler(IntradaySquareOffService service) { this(service, new JobRunTracker()); }

    @Scheduled(fixedDelayString = "${tradecore.intraday.square-off-check-interval:PT30S}")
    public void scheduledRun() { runOnce(Instant.now()); }

    public void runOnce(Instant now) {
        if (!running.compareAndSet(false, true)) { tracker.skipped("INTRADAY_SQUARE_OFF"); return; }
        Instant started = tracker.start("INTRADAY_SQUARE_OFF");
        try { int settled = service.runOnce(now); tracker.finish("INTRADAY_SQUARE_OFF", started, "SUCCESS", settled, settled, 0, 0, null); }
        catch (RuntimeException failure) { tracker.finish("INTRADAY_SQUARE_OFF", started, "FAILURE", 0, 0, 0, 1, failure.getMessage()); log.warn("Intraday square-off sweep failed category={}; a later run will retry", failure.getClass().getSimpleName()); }
        finally { running.set(false); }
    }
}
