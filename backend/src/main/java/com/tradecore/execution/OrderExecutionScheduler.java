package com.tradecore.execution;

import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.order.TradingOrderRepository;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Processes persisted pending orders only within the configured regular market session. */
@Component
public class OrderExecutionScheduler {
    private static final Logger log = LoggerFactory.getLogger(OrderExecutionScheduler.class);
    private final ExecutionSchedulingProperties properties;
    private final MarketHoursPolicy marketHoursPolicy;
    private final TradingOrderRepository orderRepository;
    private final OrderExecutionService executionService;
    private final AtomicBoolean running = new AtomicBoolean();

    public OrderExecutionScheduler(ExecutionSchedulingProperties properties, MarketHoursPolicy marketHoursPolicy,
            TradingOrderRepository orderRepository, OrderExecutionService executionService) {
        this.properties = properties;
        this.marketHoursPolicy = marketHoursPolicy;
        this.orderRepository = orderRepository;
        this.executionService = executionService;
    }

    @Scheduled(fixedDelayString = "${tradecore.execution.scheduling.interval:PT5S}")
    public void scheduledRun() { runOnce(Instant.now()); }

    public void runOnce(Instant now) {
        if (!properties.isEnabled() || !marketHoursPolicy.isRegularSession(now)) return;
        if (!running.compareAndSet(false, true)) return;
        try {
            for (var order : orderRepository.findTop100ByStatusOrderByCreatedAtAsc("PENDING")) {
                try {
                    executionService.executePending(order.getId());
                } catch (RuntimeException failure) {
                    log.warn("Virtual order execution attempt failed for order {}", order.getId(), failure);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Virtual order execution scan failed; a later scheduled run will retry", failure);
        } finally {
            running.set(false);
        }
    }
}
