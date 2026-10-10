package com.tradecore.alert;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.tradecore.admin.JobRunTracker;

/** Evaluates alerts against persisted quotes only; no market provider is called here. */
@Component
public class PriceAlertScheduler {
    private static final Logger log = LoggerFactory.getLogger(PriceAlertScheduler.class);
    private final PriceAlertService service;
    private final AtomicBoolean running = new AtomicBoolean();
    private final JobRunTracker tracker;
    @Autowired
    public PriceAlertScheduler(PriceAlertService service, JobRunTracker tracker) { this.service = service; this.tracker = tracker; }
    public PriceAlertScheduler(PriceAlertService service) { this(service, new JobRunTracker()); }

    @Scheduled(fixedDelayString = "${tradecore.alerts.processing-interval:PT30S}")
    public void scheduledCheck() { runOnce(Instant.now()); }

    public void runOnce(Instant now) {
        if (!running.compareAndSet(false, true)) { tracker.skipped("PRICE_ALERT_PROCESSING"); return; }
        Instant started = tracker.start("PRICE_ALERT_PROCESSING");
        long processed = 0, failed = 0, updated = 0;
        try {
            for (var id : service.activeAlertIds()) {
                try { if (service.process(id, now)) updated++; processed++; }
                catch (RuntimeException failure) { processed++; failed++; log.warn("Price alert processing failed for {} category={}", id, failure.getClass().getSimpleName()); }
            }
            tracker.finish("PRICE_ALERT_PROCESSING", started, failed == 0 ? "SUCCESS" : "FAILURE", processed, updated, 0, failed, null);
        } catch (RuntimeException failure) {
            tracker.finish("PRICE_ALERT_PROCESSING", started, "FAILURE", processed, updated, 0, failed + 1, failure.getMessage());
            log.warn("Price alert scan failed category={}; a later run will retry", failure.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }
}
