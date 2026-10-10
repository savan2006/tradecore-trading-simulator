package com.tradecore.execution;

import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.order.TradingOrder;
import com.tradecore.order.TradingOrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.tradecore.admin.JobRunTracker;

/** Processes persisted pending orders only within the configured regular market session. */
@Component
public class OrderExecutionScheduler {
    private static final int PAGE_SIZE = 100;
    private static final Logger log = LoggerFactory.getLogger(OrderExecutionScheduler.class);
    private final ExecutionSchedulingProperties properties;
    private final MarketHoursPolicy marketHoursPolicy;
    private final TradingOrderRepository orderRepository;
    private final OrderExecutionService executionService;
    private final int maxOrdersPerRun;
    private final AtomicBoolean running = new AtomicBoolean();
    private final JobRunTracker tracker;

    @Autowired
    public OrderExecutionScheduler(ExecutionSchedulingProperties properties, MarketHoursPolicy marketHoursPolicy,
            TradingOrderRepository orderRepository, OrderExecutionService executionService,
            @Value("${tradecore.execution.max-orders-per-run:1000}") int maxOrdersPerRun, JobRunTracker tracker) {
        this.properties = properties;
        this.marketHoursPolicy = marketHoursPolicy;
        this.orderRepository = orderRepository;
        this.executionService = executionService;
        this.maxOrdersPerRun = maxOrdersPerRun;
        this.tracker = tracker;
    }

    public OrderExecutionScheduler(ExecutionSchedulingProperties properties, MarketHoursPolicy marketHoursPolicy,
            TradingOrderRepository orderRepository, OrderExecutionService executionService) {
        this(properties, marketHoursPolicy, orderRepository, executionService, 1000, new JobRunTracker());
    }
    public OrderExecutionScheduler(ExecutionSchedulingProperties properties, MarketHoursPolicy marketHoursPolicy,
            TradingOrderRepository orderRepository, OrderExecutionService executionService, int maxOrdersPerRun) {
        this(properties, marketHoursPolicy, orderRepository, executionService, maxOrdersPerRun, new JobRunTracker());
    }

    @Scheduled(fixedDelayString = "${tradecore.execution.scheduling.interval:PT5S}")
    public void scheduledRun() { runOnce(Instant.now()); }

    public void runOnce(Instant now) {
        if (!properties.isEnabled() || !marketHoursPolicy.isRegularSession(now)) { tracker.skipped("ORDER_EXECUTION_SWEEP"); return; }
        if (!running.compareAndSet(false, true)) { tracker.skipped("ORDER_EXECUTION_SWEEP"); return; }
        Instant trackedStart = tracker.start("ORDER_EXECUTION_SWEEP");
        long processed = 0, failed = 0;
        try {
            List<UUID> pendingOrderIds = new ArrayList<>();
            int pageNumber = 0;
            int maxOrders = Math.max(0, maxOrdersPerRun);
            Page<TradingOrder> page;
            do {
                int pageSize = Math.min(PAGE_SIZE, maxOrders - pendingOrderIds.size());
                if (pageSize <= 0) break;
                page = orderRepository.findByStatusOrderByCreatedAtAscIdAsc("PENDING",
                        PageRequest.of(pageNumber++, pageSize));
                page.getContent().stream().map(TradingOrder::getId).forEach(pendingOrderIds::add);
            } while (page.hasNext() && pendingOrderIds.size() < maxOrders);

            for (UUID orderId : pendingOrderIds) {
                try {
                    executionService.executePending(orderId);
                    processed++;
                } catch (RuntimeException failure) {
                    processed++; failed++;
                    log.warn("Virtual order execution attempt failed for order {} category={}",
                            orderId, failure.getClass().getSimpleName());
                }
            }
            tracker.finish("ORDER_EXECUTION_SWEEP", trackedStart, failed == 0 ? "SUCCESS" : "FAILURE", processed, 0, 0, failed, null);
        } catch (RuntimeException failure) {
            tracker.finish("ORDER_EXECUTION_SWEEP", trackedStart, "FAILURE", processed, 0, 0, failed + 1, failure.getMessage());
            log.warn("Virtual order execution scan failed category={}; a later scheduled run will retry",
                    failure.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }
}
