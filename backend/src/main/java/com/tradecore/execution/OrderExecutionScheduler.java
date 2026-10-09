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

    @Autowired
    public OrderExecutionScheduler(ExecutionSchedulingProperties properties, MarketHoursPolicy marketHoursPolicy,
            TradingOrderRepository orderRepository, OrderExecutionService executionService,
            @Value("${tradecore.execution.max-orders-per-run:1000}") int maxOrdersPerRun) {
        this.properties = properties;
        this.marketHoursPolicy = marketHoursPolicy;
        this.orderRepository = orderRepository;
        this.executionService = executionService;
        this.maxOrdersPerRun = maxOrdersPerRun;
    }

    public OrderExecutionScheduler(ExecutionSchedulingProperties properties, MarketHoursPolicy marketHoursPolicy,
            TradingOrderRepository orderRepository, OrderExecutionService executionService) {
        this(properties, marketHoursPolicy, orderRepository, executionService, 1000);
    }

    @Scheduled(fixedDelayString = "${tradecore.execution.scheduling.interval:PT5S}")
    public void scheduledRun() { runOnce(Instant.now()); }

    public void runOnce(Instant now) {
        if (!properties.isEnabled() || !marketHoursPolicy.isRegularSession(now)) return;
        if (!running.compareAndSet(false, true)) return;
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
                } catch (RuntimeException failure) {
                    log.warn("Virtual order execution attempt failed for order {}", orderId, failure);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Virtual order execution scan failed; a later scheduled run will retry", failure);
        } finally {
            running.set(false);
        }
    }
}
