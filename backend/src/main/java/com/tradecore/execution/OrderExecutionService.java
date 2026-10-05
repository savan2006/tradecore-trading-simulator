package com.tradecore.execution;

import com.tradecore.account.TradingAccount;
import com.tradecore.account.TradingAccountRepository;
import com.tradecore.audit.AuditService;
import com.tradecore.ledger.LedgerEntry;
import com.tradecore.ledger.LedgerEntryRepository;
import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.market.MarketQuote;
import com.tradecore.market.MarketQuoteRepository;
import com.tradecore.order.OrderEventRepository;
import com.tradecore.order.OrderEvent;
import com.tradecore.order.TradingOrder;
import com.tradecore.order.TradingOrderRepository;
import com.tradecore.portfolio.Position;
import com.tradecore.portfolio.PositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Settles complete virtual fills from the latest eligible persisted quote. */
@Service
public class OrderExecutionService {
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(600);
    private final TradingOrderRepository orderRepository;
    private final TradingAccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final MarketQuoteRepository quoteRepository;
    private final ExecutionRepository executionRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final OrderEventRepository eventRepository;
    private final MarketHoursPolicy marketHoursPolicy;
    private final AuditService auditService;

    public OrderExecutionService(TradingOrderRepository orderRepository, TradingAccountRepository accountRepository,
            PositionRepository positionRepository, MarketQuoteRepository quoteRepository,
            ExecutionRepository executionRepository, LedgerEntryRepository ledgerRepository,
            OrderEventRepository eventRepository, MarketHoursPolicy marketHoursPolicy, AuditService auditService) {
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.quoteRepository = quoteRepository;
        this.executionRepository = executionRepository;
        this.ledgerRepository = ledgerRepository;
        this.eventRepository = eventRepository;
        this.marketHoursPolicy = marketHoursPolicy;
        this.auditService = auditService;
    }

    @Transactional
    public boolean executePending(UUID orderId) {
        return executePending(orderId, false);
    }

    /** Executes a system-generated intraday square-off at a fresh persisted quote after session close. */
    @Transactional
    public boolean executePendingForSquareOff(UUID orderId) {
        return executePending(orderId, true);
    }

    private boolean executePending(UUID orderId, boolean squareOff) {
        Instant now = Instant.now();
        TradingOrder order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || !"PENDING".equals(order.getStatus()) || order.getRemainingQuantity() <= 0
                || order.getInstrument() == null || !order.getInstrument().isTradable()
                || (squareOff ? !"INTRADAY".equals(order.getTradingMode()) || !"SELL".equals(order.getSide())
                        || !"MARKET".equals(order.getOrderType())
                        : !marketHoursPolicy.isRegularSession(now))) return false;

        TradingAccount account = accountRepository.findByIdForUpdate(order.getAccount().getId()).orElse(null);
        if (account == null || !account.getId().equals(order.getAccount().getId()) || !"ACTIVE".equals(account.getStatus())) return false;

        MarketQuote quote = quoteRepository.findByInstrument_Id(order.getInstrument().getId()).orElse(null);
        if (!eligible(quote, now) || (!squareOff && !"OPEN".equals(quote.getMarketStatus()))) return false;
        BigDecimal executionPrice = marketPrice(quote, order.getSide());
        if (executionPrice == null || executionPrice.signum() <= 0) return false;
        if ("LIMIT".equals(order.getOrderType())) {
            if (order.getLimitPrice() == null || order.getLimitPrice().signum() <= 0) return false;
            int comparison = executionPrice.compareTo(order.getLimitPrice());
            if (("BUY".equals(order.getSide()) && comparison > 0)
                    || ("SELL".equals(order.getSide()) && comparison < 0)) return false;
        } else if (!"MARKET".equals(order.getOrderType())) return false;

        long quantity = order.getRemainingQuantity();
        if ("BUY".equals(order.getSide()) && order.getReservedAmount().signum() <= 0) return false;
        BigDecimal notional = executionPrice.multiply(BigDecimal.valueOf(quantity)).setScale(4, RoundingMode.HALF_UP);
        Position position = positionRepository.findForUpdate(account.getId(), order.getInstrument().getId(), order.getTradingMode()).orElse(null);
        if ("BUY".equals(order.getSide())) {
            BigDecimal topUp = notional.subtract(order.getReservedAmount()).max(BigDecimal.ZERO);
            if (order.getReservedAmount().signum() < 0 || account.getReservedBalance().compareTo(order.getReservedAmount()) < 0
                    || account.getAvailableBalance().compareTo(topUp) < 0) return false;
            if (position == null) position = positionRepository.save(new Position(account, order.getInstrument(), order.getTradingMode(), now));
        } else if (!"SELL".equals(order.getSide()) || position == null
                || position.getQuantity() < quantity || position.getReservedQuantity() < quantity) return false;

        Execution execution = executionRepository.saveAndFlush(new Execution(account, order, order.getInstrument(),
                order.getSide(), order.getTradingMode(), quantity, executionPrice, now,
                quote.getLastPrice(), quote.getMarketAt()));
        if ("BUY".equals(order.getSide())) {
            account.settleBuy(order.getReservedAmount(), notional, now);
            position.settleBuy(quantity, executionPrice, now);
            ledgerRepository.saveAndFlush(LedgerEntry.trade(account, execution, notional.negate(),
                    "Virtual BUY execution " + order.getId(), now));
        } else {
            position.settleSell(quantity, executionPrice, now);
            account.creditSale(notional, now);
            ledgerRepository.saveAndFlush(LedgerEntry.trade(account, execution, notional,
                    "Virtual SELL execution " + order.getId(), now));
        }
        order.fill(quantity, now);
        eventRepository.saveAndFlush(new OrderEvent(order, "PENDING", "FILLED", "ORDER_FILLED",
                "Virtual order filled from persisted market quote", now));
        auditService.record(order.getAccount().getUser().getEmail(), "ORDER_EXECUTED", "ORDER", order.getId(),
                "{\"symbol\":\"" + order.getInstrument().getSymbol() + "\",\"side\":\""
                        + order.getSide() + "\",\"mode\":\"" + order.getTradingMode()
                        + "\",\"quantity\":" + quantity + "}");
        if (squareOff) {
            auditService.record(order.getAccount().getUser().getEmail(), "INTRADAY_SQUARE_OFF", "POSITION",
                    position.getId(), "{\"orderId\":\"" + order.getId() + "\",\"symbol\":\""
                            + order.getInstrument().getSymbol() + "\",\"quantity\":" + quantity + "}");
        }
        return true;
    }

    private static boolean eligible(MarketQuote quote, Instant now) {
        if (quote == null || !"LIVE".equals(quote.getDataStatus()) || quote.getReceivedAt() == null
                || quote.getProviderUpdatedAt() == null || quote.getProviderUpdatedAt().isBefore(now.minus(MAX_QUOTE_AGE))
                || quote.getProviderUpdatedAt().isAfter(now) || quote.getLastPrice() == null
                || quote.getLastPrice().signum() <= 0) return false;
        return quote.getMarketAt() == null || !quote.getMarketAt().isAfter(now);
    }

    private static BigDecimal marketPrice(MarketQuote quote, String side) {
        BigDecimal bookPrice = "BUY".equals(side) ? quote.getAskPrice() : quote.getBidPrice();
        return bookPrice != null && bookPrice.signum() > 0 ? bookPrice : quote.getLastPrice();
    }
}
