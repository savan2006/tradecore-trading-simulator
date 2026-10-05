package com.tradecore.admin;

import com.tradecore.account.TradingAccountRepository;
import com.tradecore.execution.ExecutionSchedulingProperties;
import com.tradecore.identity.UserRepository;
import com.tradecore.market.InstrumentRepository;
import com.tradecore.market.MarketDataRefreshProperties;
import com.tradecore.market.MarketDataRefreshScheduler;
import com.tradecore.market.MarketQuoteRepository;
import com.tradecore.notification.NotificationRepository;
import com.tradecore.order.TradingOrder;
import com.tradecore.order.TradingOrderRepository;
import com.tradecore.portfolio.PositionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminOperationsService {
    private static final int MAX_PAGE = 100_000;
    private static final int MAX_SIZE = 100;
    private static final Set<String> ORDER_STATES = Set.of("CREATED", "VALIDATING", "ACCEPTED", "PENDING",
            "PARTIALLY_FILLED", "FILLED", "CANCELLED", "REJECTED", "FAILED");
    private final UserRepository users;
    private final TradingAccountRepository accounts;
    private final TradingOrderRepository orders;
    private final PositionRepository positions;
    private final NotificationRepository notifications;
    private final InstrumentRepository instruments;
    private final MarketQuoteRepository quotes;
    private final MarketDataRefreshScheduler marketScheduler;
    private final MarketDataRefreshProperties marketProperties;
    private final ExecutionSchedulingProperties executionProperties;
    private final String squareOffInterval;

    public AdminOperationsService(UserRepository users, TradingAccountRepository accounts,
            TradingOrderRepository orders, PositionRepository positions, NotificationRepository notifications,
            InstrumentRepository instruments, MarketQuoteRepository quotes,
            MarketDataRefreshScheduler marketScheduler, MarketDataRefreshProperties marketProperties,
            ExecutionSchedulingProperties executionProperties,
            @Value("${tradecore.intraday.square-off-check-interval:PT30S}") String squareOffInterval) {
        this.users = users;
        this.accounts = accounts;
        this.orders = orders;
        this.positions = positions;
        this.notifications = notifications;
        this.instruments = instruments;
        this.quotes = quotes;
        this.marketScheduler = marketScheduler;
        this.marketProperties = marketProperties;
        this.executionProperties = executionProperties;
        this.squareOffInterval = squareOffInterval;
    }

    @Transactional(readOnly = true)
    public AdminOverviewResponse overview() {
        Instant latest = marketScheduler.getLastSuccessfulQuoteRunAt();
        String status = marketScheduler.getLastQuoteOutcome();
        return new AdminOverviewResponse(users.count(), accounts.countByStatus("ACTIVE"),
                orders.countByStatus("PENDING"), orders.countByStatus("FILLED"),
                orders.countByStatus("CANCELLED"), positions.countByQuantityGreaterThan(0),
                notifications.countByReadAtIsNull(), instruments.countByTradableTrue(), latest, status);
    }

    @Transactional(readOnly = true)
    public AdminUserPageResponse users(String search, int page, int size) {
        validatePage(page, size);
        String term = search == null || search.isBlank() ? null : search.trim();
        if (term != null && term.length() > 120) throw badRequest("search must be at most 120 characters");
        Page<AdminUserResponse> results = users.searchAdminUsers(term, pageRequest(page, size, "createdAt"))
                .map(u -> new AdminUserResponse(u.getId(), u.getEmail(), u.getDisplayName(),
                        u.getRole(), u.getStatus(), u.getCreatedAt()));
        return new AdminUserPageResponse(results.getContent(), page, size, results.getTotalElements(),
                results.getTotalPages(), results.hasNext());
    }

    @Transactional(readOnly = true)
    public AdminOrderPageResponse orders(String status, String symbol, String tradingMode,
            Instant from, Instant to, int page, int size) {
        validatePage(page, size);
        if (from != null && to != null && from.isAfter(to)) throw badRequest("from must be <= to");
        String normalizedStatus = normalize(status);
        if (normalizedStatus != null && !ORDER_STATES.contains(normalizedStatus)) {
            throw badRequest("status is not a supported order state");
        }
        String normalizedSymbol = normalize(symbol);
        if (normalizedSymbol != null && normalizedSymbol.length() > 32) throw badRequest("symbol is too long");
        String normalizedMode = normalize(tradingMode);
        if (normalizedMode != null && !Set.of("DELIVERY", "INTRADAY").contains(normalizedMode)) {
            throw badRequest("tradingMode must be DELIVERY or INTRADAY");
        }
        Specification<TradingOrder> filters = (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (normalizedStatus != null) predicates.add(builder.equal(root.get("status"), normalizedStatus));
            if (normalizedSymbol != null) predicates.add(builder.equal(
                    builder.upper(root.join("instrument").get("symbol")), normalizedSymbol));
            if (normalizedMode != null) predicates.add(builder.equal(root.get("tradingMode"), normalizedMode));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.<Instant>get("createdAt"), to));
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Page<AdminOrderResponse> results = orders.findAll(filters, pageRequest(page, size, "createdAt"))
                .map(AdminOperationsService::orderResponse);
        return new AdminOrderPageResponse(results.getContent(), page, size, results.getTotalElements(),
                results.getTotalPages(), results.hasNext());
    }

    @Transactional(readOnly = true)
    public AdminMarketStatusResponse marketStatus() {
        return new AdminMarketStatusResponse(marketProperties.isQuoteEnabled(),
                marketProperties.getQuoteInterval().toString(), marketScheduler.getLastQuoteAttemptAt(),
                marketScheduler.getLastQuoteOutcome(), marketScheduler.getLastSuccessfulQuoteRunAt(),
                marketScheduler.getQuoteFailureCount(), marketProperties.isCandleEnabled(),
                marketProperties.getCandleCron(), marketScheduler.getLastCandleAttemptAt(),
                marketScheduler.getLastCandleOutcome(), marketScheduler.getLastSuccessfulCandleRunAt(),
                marketScheduler.getCandleFailureCount(), executionProperties.isEnabled(),
                executionProperties.getInterval().toString(), squareOffInterval,
                quotes.findLatestReceivedAt().orElse(null));
    }

    private static AdminOrderResponse orderResponse(TradingOrder order) {
        return new AdminOrderResponse(order.getId(), order.getInstrument().getExchange(),
                order.getInstrument().getSymbol(), order.getSide(), order.getOrderType(), order.getTradingMode(),
                order.getRequestedQuantity(), order.getExecutedQuantity(), order.getRemainingQuantity(),
                order.getStatus(), order.getCreatedAt(), order.getUpdatedAt());
    }

    private static PageRequest pageRequest(int page, int size, String timestamp) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc(timestamp), Sort.Order.desc("id")));
    }
    private static void validatePage(int page, int size) {
        if (page < 0 || page > MAX_PAGE) throw badRequest("page must be between 0 and " + MAX_PAGE);
        if (size < 1 || size > MAX_SIZE) throw badRequest("size must be between 1 and " + MAX_SIZE);
    }
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
