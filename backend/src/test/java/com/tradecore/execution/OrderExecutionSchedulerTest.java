package com.tradecore.execution;

import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.market.MarketDataRefreshProperties;
import com.tradecore.market.MarketSession;
import com.tradecore.market.MarketSessionRepository;
import com.tradecore.order.TradingOrder;
import com.tradecore.order.TradingOrderRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
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
    void configuredHolidayPreventsOrderExecutionScan() {
        MarketSessionRepository calendar = mock(MarketSessionRepository.class);
        LocalDate holiday = LocalDate.parse("2026-10-05");
        when(calendar.findByTradingDateAndActiveTrue(holiday)).thenReturn(Optional.of(
                new MarketSession(holiday, true, null, null, "test holiday")));
        MarketHoursPolicy calendarPolicy = new MarketHoursPolicy(new MarketDataRefreshProperties(), calendar);
        var scheduler = new OrderExecutionScheduler(properties, calendarPolicy, orders, execution);
        scheduler.runOnce(Instant.parse("2026-10-05T04:30:00Z"));
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
        when(orders.findByStatusOrderByCreatedAtAscIdAsc(eq("PENDING"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pending)));
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
    void oneOrderFailureDoesNotStopTheCurrentSweep() {
        when(hours.isRegularSession(any())).thenReturn(true);
        TradingOrder failing = mock(TradingOrder.class), later = mock(TradingOrder.class);
        UUID failingId = UUID.randomUUID(), laterId = UUID.randomUUID();
        when(failing.getId()).thenReturn(failingId);
        when(later.getId()).thenReturn(laterId);
        when(orders.findByStatusOrderByCreatedAtAscIdAsc(eq("PENDING"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(failing, later)));
        when(execution.executePending(failingId)).thenThrow(new IllegalStateException("transient database fault"));
        OrderExecutionScheduler scheduler = scheduler();
        scheduler.runOnce(Instant.now());
        verify(execution).executePending(failingId);
        verify(execution).executePending(laterId);
    }

    @Test
    void sweepUsesStablePagesAndHonorsThePerRunLimit() {
        when(hours.isRegularSession(any())).thenReturn(true);
        List<TradingOrder> pending = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            TradingOrder order = mock(TradingOrder.class);
            when(order.getId()).thenReturn(UUID.randomUUID());
            pending.add(order);
        }
        when(orders.findByStatusOrderByCreatedAtAscIdAsc(eq("PENDING"), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    int start = (int) pageable.getOffset();
                    return new PageImpl<>(pending.subList(start, Math.min(start + pageable.getPageSize(), pending.size())),
                            pageable, pending.size());
                });

        new OrderExecutionScheduler(properties, hours, orders, execution, 150).runOnce(Instant.now());

        verify(orders, times(2)).findByStatusOrderByCreatedAtAscIdAsc(eq("PENDING"), any(Pageable.class));
        verify(execution, times(150)).executePending(any(UUID.class));
    }

    private OrderExecutionScheduler scheduler() {
        return new OrderExecutionScheduler(properties, hours, orders, execution);
    }
}
