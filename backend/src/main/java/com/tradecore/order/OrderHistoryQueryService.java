package com.tradecore.order;

import com.tradecore.execution.ExecutionRepository;
import com.tradecore.execution.TradeHistoryPageResponse;
import com.tradecore.execution.TradeHistoryResponse;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderHistoryQueryService {
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final int MAX_PAGE = 100_000;
    private static final Set<String> ORDER_STATES = Set.of("CREATED", "VALIDATING", "ACCEPTED", "PENDING",
            "PARTIALLY_FILLED", "FILLED", "CANCELLED", "REJECTED", "FAILED");
    private final TradingOrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final OrderEventRepository eventRepository;

    public OrderHistoryQueryService(TradingOrderRepository orderRepository, ExecutionRepository executionRepository,
            OrderEventRepository eventRepository) {
        this.orderRepository = orderRepository;
        this.executionRepository = executionRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional(readOnly = true)
    public OrderHistoryPageResponse orders(String authenticatedEmail, String status, String symbol,
            String tradingMode, Instant from, Instant to, int page, int size) {
        validatePage(page, size);
        if (from != null && to != null && from.isAfter(to)) {
            throw badRequest("from must be less than or equal to to");
        }
        String normalizedStatus = normalizeOptional(status);
        if (normalizedStatus != null && !ORDER_STATES.contains(normalizedStatus)) {
            throw badRequest("status is not a supported order state");
        }
        String normalizedSymbol = normalizeOptional(symbol);
        if (normalizedSymbol != null && normalizedSymbol.length() > 32) {
            throw badRequest("symbol exceeds the supported length");
        }
        String normalizedMode = normalizeOptional(tradingMode);
        if (normalizedMode != null && !Set.of("DELIVERY", "INTRADAY").contains(normalizedMode)) {
            throw badRequest("tradingMode must be DELIVERY or INTRADAY");
        }
        Specification<TradingOrder> filters = (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(builder.lower(root.get("account").get("user").get("email")),
                    normalizeEmail(authenticatedEmail)));
            if (normalizedStatus != null) predicates.add(builder.equal(root.get("status"), normalizedStatus));
            if (normalizedSymbol != null) predicates.add(builder.equal(
                    builder.upper(root.join("instrument").get("symbol")), normalizedSymbol));
            if (normalizedMode != null) predicates.add(builder.equal(root.get("tradingMode"), normalizedMode));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.<Instant>get("createdAt"), to));
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Page<OrderHistoryResponse> result = orderRepository.findAll(filters, pageRequest(page, size, "createdAt"))
                .map(OrderHistoryResponse::from);
        return new OrderHistoryPageResponse(result.getContent(), page, size, result.getTotalElements(),
                result.getTotalPages(), result.hasNext());
    }

    @Transactional(readOnly = true)
    public OrderHistoryResponse order(String authenticatedEmail, UUID orderId) {
        return orderRepository.findByIdAndAccount_User_Email(orderId, normalizeEmail(authenticatedEmail))
                .map(OrderHistoryResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order was not found"));
    }

    @Transactional(readOnly = true)
    public List<OrderEventResponse> events(String authenticatedEmail, UUID orderId) {
        orderRepository.findByIdAndAccount_User_Email(orderId, normalizeEmail(authenticatedEmail))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order was not found"));
        return eventRepository.findByOrder_IdOrderByOccurredAtAscIdAsc(orderId).stream()
                .map(OrderEventResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TradeHistoryPageResponse trades(String authenticatedEmail, int page, int size) {
        validatePage(page, size);
        Page<TradeHistoryResponse> result = executionRepository.findOwnedExecutions(
                        normalizeEmail(authenticatedEmail), pageRequest(page, size, "executedAt"))
                .map(TradeHistoryResponse::from);
        return new TradeHistoryPageResponse(result.getContent(), page, size, result.getTotalElements(),
                result.getTotalPages(), result.hasNext());
    }

    private static PageRequest pageRequest(int page, int size, String timestampField) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc(timestampField), Sort.Order.desc("id")));
    }
    private static void validatePage(int page, int size) {
        if (page < 0 || page > MAX_PAGE) throw badRequest("page must be between 0 and " + MAX_PAGE);
        if (size < 1 || size > MAX_SIZE) throw badRequest("size must be between 1 and " + MAX_SIZE);
    }
    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toUpperCase(Locale.ROOT);
    }
    private static String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
