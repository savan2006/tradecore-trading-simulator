package com.tradecore.alert;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Evaluates alerts against persisted quotes only; no market provider is called here. */
@Component
public class PriceAlertScheduler {
    private static final Logger log = LoggerFactory.getLogger(PriceAlertScheduler.class);
    private final PriceAlertService service;
    private final AtomicBoolean running = new AtomicBoolean();
    public PriceAlertScheduler(PriceAlertService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${tradecore.alerts.processing-interval:PT30S}")
    public void scheduledCheck() { runOnce(Instant.now()); }

    public void runOnce(Instant now) {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (var id : service.activeAlertIds()) {
                try { service.process(id, now); }
                catch (RuntimeException failure) { log.warn("Price alert processing failed for {}", id, failure); }
            }
        } finally {
            running.set(false);
        }
    }
}
