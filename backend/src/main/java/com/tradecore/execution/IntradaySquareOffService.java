package com.tradecore.execution;

import com.tradecore.account.TradingAccount;
import com.tradecore.account.TradingAccountRepository;
import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.order.OrderCancellationService;
import com.tradecore.order.OrderEvent;
import com.tradecore.order.OrderEventRepository;
import com.tradecore.order.TradingOrder;
import com.tradecore.order.TradingOrderRepository;
import com.tradecore.portfolio.Position;
import com.tradecore.portfolio.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Closes intraday exposure in the configured pre-close window, using regular order settlement. */
@Service
public class IntradaySquareOffService {
    private static final Logger log = LoggerFactory.getLogger(IntradaySquareOffService.class);
    private final MarketHoursPolicy marketHours;
    private final TradingOrderRepository orders;
    private final PositionRepository positions;
    private final TradingAccountRepository accounts;
    private final OrderCancellationService cancellations;
    private final OrderExecutionService execution;
    private final OrderEventRepository events;
    private final TransactionTemplate transactionTemplate;

    public IntradaySquareOffService(MarketHoursPolicy marketHours, TradingOrderRepository orders,
            PositionRepository positions, TradingAccountRepository accounts,
            OrderCancellationService cancellations, OrderExecutionService execution,
            OrderEventRepository events, PlatformTransactionManager transactionManager) {
        this.marketHours = marketHours; this.orders = orders; this.positions = positions;
        this.accounts = accounts; this.cancellations = cancellations; this.execution = execution;
        this.events = events;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Returns without work before the square-off window or on holidays. Retries remain state-idempotent. */
    public int runOnce(Instant now) {
        if (!marketHours.isSquareOffWindow(now)) return 0;
        int settled = 0;
        for (UUID orderId : orders.findPendingIntradayOrderIds()) {
            try { cancellations.cancelForSquareOff(orderId); }
            catch (RuntimeException failure) {
                log.warn("Pending intraday order could not be cancelled during square-off: {} category={}",
                        orderId, failure.getClass().getSimpleName());
            }
        }
        for (UUID positionId : positions.findOpenIntradayPositionIds()) {
            try {
                Boolean didSettle = transactionTemplate.execute(status -> squareOffPosition(positionId));
                if (Boolean.TRUE.equals(didSettle)) settled++;
            }
            catch (IneligibleSquareOffException ignored) {
                log.debug("Intraday position {} remains open until a fresh eligible quote is available", positionId);
            }
        }
        return settled;
    }

    public boolean squareOffPosition(UUID positionId) {
        Position snapshot = positions.findById(positionId).orElse(null);
        if (snapshot == null || !"INTRADAY".equals(snapshot.getTradingMode())) return false;
        // Preserve execution's account -> position lock order to serialize with user orders and settlement.
        TradingAccount account = accounts.findByIdForUpdate(snapshot.getAccount().getId()).orElse(null);
        if (account == null) return false;
        Position position = positions.findByIdForUpdate(positionId).orElse(null);
        if (position == null || position.getQuantity() <= position.getReservedQuantity()) return false;

        long sellable = position.getQuantity() - position.getReservedQuantity();
        Instant now = Instant.now();
        position.reserve(sellable, now);
        TradingOrder order = orders.saveAndFlush(new TradingOrder(account, position.getInstrument(),
                "SELL", "MARKET", "INTRADAY", sellable, null, BigDecimal.ZERO.setScale(4), now));
        events.saveAndFlush(new OrderEvent(order, null, "PENDING", "ORDER_PLACED",
                "System-generated intraday square-off order", now));
        if (!execution.executePendingForSquareOff(order.getId())) {
            throw new IneligibleSquareOffException();
        }
        return true;
    }

    private static final class IneligibleSquareOffException extends RuntimeException { }
}
