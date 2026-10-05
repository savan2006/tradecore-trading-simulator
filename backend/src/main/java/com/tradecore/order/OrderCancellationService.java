package com.tradecore.order;

import com.tradecore.account.TradingAccount;
import com.tradecore.account.TradingAccountRepository;
import com.tradecore.audit.AuditService;
import com.tradecore.portfolio.Position;
import com.tradecore.portfolio.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderCancellationService {
    private final TradingOrderRepository orderRepository;
    private final TradingAccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final OrderEventRepository eventRepository;
    private final AuditService auditService;

    public OrderCancellationService(TradingOrderRepository orderRepository,
            TradingAccountRepository accountRepository, PositionRepository positionRepository,
            OrderEventRepository eventRepository, AuditService auditService) {
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.eventRepository = eventRepository;
        this.auditService = auditService;
    }

    @Transactional
    public OrderCancellationResponse cancel(String authenticatedEmail, UUID orderId) {
        return cancelInternal(orderId, authenticatedEmail, false);
    }

    /** System path used only to retire pending intraday orders at session end. */
    @Transactional
    public OrderCancellationResponse cancelForSquareOff(UUID orderId) {
        return cancelInternal(orderId, null, true);
    }

    private OrderCancellationResponse cancelInternal(UUID orderId, String authenticatedEmail, boolean systemIntradayOnly) {
        TradingOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order was not found"));
        if (systemIntradayOnly && !"INTRADAY".equals(order.getTradingMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "System square-off can cancel intraday orders only");
        }
        String ownerEmail = order.getAccount().getUser().getEmail();
        if (!systemIntradayOnly && !ownerEmail.equals(authenticatedEmail.trim().toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order was not found");
        }
        // Repeated cancellation is an idempotent read: it cannot release resources or add another event.
        if ("CANCELLED".equals(order.getStatus())) {
            return OrderCancellationResponse.cancelled(order, BigDecimal.ZERO.setScale(4), 0);
        }
        if (!"PENDING".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending orders can be cancelled");
        }

        Instant now = Instant.now();
        TradingAccount account = accountRepository.findByIdForUpdate(order.getAccount().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trading account was not found"));
        BigDecimal releasedFunds = BigDecimal.ZERO.setScale(4);
        long releasedQuantity = 0;
        if ("BUY".equals(order.getSide())) {
            releasedFunds = order.getReservedAmount();
            account.releaseReservedFunds(releasedFunds, now);
        } else if ("SELL".equals(order.getSide())) {
            Position position = positionRepository.findForUpdate(account.getId(), order.getInstrument().getId(), order.getTradingMode())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Reserved sell position was not found"));
            releasedQuantity = order.getRemainingQuantity();
            position.releaseReservation(releasedQuantity, now);
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order side is invalid");
        }

        order.cancel(now);
        eventRepository.saveAndFlush(new OrderEvent(order, "PENDING", "CANCELLED", "ORDER_CANCELLED",
                "Order cancelled; unfilled resources released", now));
        auditService.record(systemIntradayOnly ? null : ownerEmail,
                systemIntradayOnly ? "INTRADAY_ORDER_CANCELLED_BY_SQUARE_OFF" : "ORDER_CANCELLED",
                "ORDER", order.getId(), systemIntradayOnly ? "{\"source\":\"INTRADAY_SQUARE_OFF\"}" : null);
        return OrderCancellationResponse.cancelled(order, releasedFunds, releasedQuantity);
    }
}
