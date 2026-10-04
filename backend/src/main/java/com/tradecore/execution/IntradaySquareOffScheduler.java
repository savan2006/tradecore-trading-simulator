package com.tradecore.execution;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Rechecks after session close; row locks and order/position states make retries safe. */
@Component
public class IntradaySquareOffScheduler {
    private static final Logger log = LoggerFactory.getLogger(IntradaySquareOffScheduler.class);
    private final IntradaySquareOffService service;
    private final AtomicBoolean running = new AtomicBoolean();
    public IntradaySquareOffScheduler(IntradaySquareOffService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${tradecore.intraday.square-off-check-interval:PT30S}")
    public void scheduledRun() { runOnce(Instant.now()); }

    public void runOnce(Instant now) {
        if (!running.compareAndSet(false, true)) return;
        try { service.runOnce(now); }
        catch (RuntimeException failure) { log.warn("Intraday square-off sweep failed; a later run will retry", failure); }
        finally { running.set(false); }
    }
}
