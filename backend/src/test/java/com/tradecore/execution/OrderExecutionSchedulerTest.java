package com.tradecore.execution;

import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.order.TradingOrder;
import com.tradecore.order.TradingOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class OrderExecutionSchedulerTest {
    private final ExecutionSchedulingProperties properties = new ExecutionSchedulingProperties();
    private final MarketHoursPolicy hours = mock(MarketHoursPolicy.class);
    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final OrderExecutionService execution = mock(OrderExecutionService.class);

    @Test
    void schedulerDoesNotScanOutsideConfiguredMarketHours() {
        when(hours.isRegularSession(any())).thenReturn(false);
        scheduler().runOnce(Instant.now());
        verifyNoInteractions(orders, execution);
    }

    @Test
    void disabledSchedulerDoesNotScanOrders() {
        properties.setEnabled(false);
        when(hours.isRegularSession(any())).thenReturn(true);
        scheduler().runOnce(Instant.now());
        verifyNoInteractions(orders, execution);
    }

    @Test
    void concurrentSchedulerRunsDoNotOverlap() throws Exception {
        when(hours.isRegularSession(any())).thenReturn(true);
        TradingOrder pending = mock(TradingOrder.class);
        UUID id = UUID.randomUUID(); when(pending.getId()).thenReturn(id);
        when(orders.findTop100ByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(pending));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(execution.executePending(id)).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        OrderExecutionScheduler scheduler = scheduler();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> scheduler.runOnce(Instant.now()));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.runOnce(Instant.now());
            release.countDown();
            first.get(3, TimeUnit.SECONDS);
            verify(execution, times(1)).executePending(id);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void oneRunFailureDoesNotPreventLaterScheduledRun() {
        when(hours.isRegularSession(any())).thenReturn(true);
        TradingOrder pending = mock(TradingOrder.class);
        UUID id = UUID.randomUUID(); when(pending.getId()).thenReturn(id);
        when(orders.findTop100ByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(pending));
        when(execution.executePending(id)).thenThrow(new IllegalStateException("transient database fault")).thenReturn(true);
        OrderExecutionScheduler scheduler = scheduler();
        scheduler.runOnce(Instant.now());
        scheduler.runOnce(Instant.now());
        verify(execution, times(2)).executePending(id);
    }

    private OrderExecutionScheduler scheduler() {
        return new OrderExecutionScheduler(properties, hours, orders, execution);
    }
}
