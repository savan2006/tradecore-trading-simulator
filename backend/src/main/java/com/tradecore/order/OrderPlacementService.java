package com.tradecore.order;

import com.tradecore.account.TradingAccount;
import com.tradecore.account.TradingAccountRepository;
import com.tradecore.audit.AuditService;
import com.tradecore.idempotency.IdempotencyRecord;
import com.tradecore.idempotency.IdempotencyRecordRepository;
import com.tradecore.identity.User;
import com.tradecore.identity.UserRepository;
import com.tradecore.market.Instrument;
import com.tradecore.market.InstrumentRepository;
import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.market.MarketQuote;
import com.tradecore.market.MarketQuoteRepository;
import com.tradecore.portfolio.Position;
import com.tradecore.portfolio.PositionRepository;
import com.tradecore.risk.RiskLimit;
import com.tradecore.risk.RiskLimitRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class OrderPlacementService {
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(600);
    private static final String ORDER_PLACED = "ORDER_PLACED";
    private static final String PENDING = "PENDING";

    private final UserRepository userRepository;
    private final TradingAccountRepository accountRepository;
    private final InstrumentRepository instrumentRepository;
    private final MarketQuoteRepository quoteRepository;
    private final PositionRepository positionRepository;
    private final RiskLimitRepository riskLimitRepository;
    private final TradingOrderRepository orderRepository;
    private final OrderEventRepository orderEventRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final MarketHoursPolicy marketHoursPolicy;
    private final AuditService auditService;

    public OrderPlacementService(UserRepository userRepository, TradingAccountRepository accountRepository,
            InstrumentRepository instrumentRepository, MarketQuoteRepository quoteRepository,
            PositionRepository positionRepository, RiskLimitRepository riskLimitRepository,
            TradingOrderRepository orderRepository, OrderEventRepository orderEventRepository,
            IdempotencyRecordRepository idempotencyRepository, MarketHoursPolicy marketHoursPolicy,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.instrumentRepository = instrumentRepository;
        this.quoteRepository = quoteRepository;
        this.positionRepository = positionRepository;
        this.riskLimitRepository = riskLimitRepository;
        this.orderRepository = orderRepository;
        this.orderEventRepository = orderEventRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.marketHoursPolicy = marketHoursPolicy;
        this.auditService = auditService;
    }

    @Transactional
    public OrderPlacementResponse placeOrder(String authenticatedEmail, OrderPlacementRequest request,
            String suppliedIdempotencyKey) {
        Instant now = Instant.now();
        User user = userRepository.findByEmail(authenticatedEmail.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> notFound("Authenticated user was not found"));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw forbidden("User account is not active");
        }

        // Every order for one account takes this database row lock before checking funds/positions.
        TradingAccount account = accountRepository.findByUserIdForUpdate(user.getId())
                .orElseThrow(() -> notFound("Trading account was not found"));
        if (!"ACTIVE".equals(account.getStatus())) {
            throw conflict("Trading account is not active");
        }

        String exchange = normalized(request.exchange());
        String symbol = normalized(request.symbol());
        String side = normalized(request.side());
        String orderType = normalized(request.orderType());
        String tradingMode = normalized(request.tradingMode());
        validateOrderFields(request, side, orderType, tradingMode);

        Instrument instrument = instrumentRepository.findByExchangeAndSymbol(exchange, symbol)
                .filter(Instrument::isTradable)
                .orElseThrow(() -> notFound("Unsupported or non-tradable instrument"));
        if (!account.getCurrency().equals(instrument.getCurrency())) {
            throw unprocessable("Instrument currency does not match the trading account");
        }

        String idempotencyKey = normalizeIdempotencyKey(suppliedIdempotencyKey);
        String fingerprint = requestFingerprint(exchange, symbol, side, orderType, tradingMode,
                request.quantity(), normalizedPrice(request.limitPrice()), normalizedPrice(request.triggerPrice()));
        if (idempotencyKey != null) {
            var existing = idempotencyRepository.findByAccount_IdAndIdempotencyKey(account.getId(), idempotencyKey);
            if (existing.isPresent()) {
                IdempotencyRecord record = existing.get();
                if (!record.getRequestFingerprint().equals(fingerprint)) {
                    throw conflict("Idempotency key was already used for a different order request");
                }
                if (!"COMPLETED".equals(record.getState()) || record.getOriginalOrder() == null) {
                    throw conflict("An order request with this idempotency key is not complete");
                }
                return OrderPlacementResponse.from(record.getOriginalOrder());
            }
        }

        MarketQuote quote = quoteRepository.findByInstrument_Id(instrument.getId())
                .orElseThrow(() -> unprocessable("A current market quote is required"));
        validateQuote(quote, now);
        if ("MARKET".equals(orderType) && !marketHoursPolicy.isRegularSession(now)) {
            throw unprocessable("Market orders require an open regular market session");
        }

        BigDecimal referencePrice = referencePrice(request, side, orderType, quote);
        BigDecimal reservation = referencePrice.multiply(BigDecimal.valueOf(request.quantity()))
                .setScale(4, RoundingMode.CEILING);
        validateRiskLimits(account, instrument, request.quantity(), reservation, now);

        Position sellPosition = null;
        if ("BUY".equals(side)) {
            if (account.getAvailableBalance().compareTo(reservation) < 0) {
                throw unprocessable("Insufficient available virtual funds");
            }
            account.reserveFunds(reservation, now);
        } else {
            sellPosition = positionRepository.findForUpdate(account.getId(), instrument.getId(), tradingMode)
                    .orElseThrow(() -> unprocessable("No sellable position exists for this instrument and mode"));
            if (sellPosition.getQuantity() - sellPosition.getReservedQuantity() < request.quantity()) {
                throw unprocessable("Insufficient sellable position quantity");
            }
            sellPosition.reserve(request.quantity(), now);
        }

        TradingOrder order = new TradingOrder(account, instrument, side, orderType, tradingMode,
                request.quantity(), request.limitPrice(), request.triggerPrice(),
                "BUY".equals(side) ? reservation : BigDecimal.ZERO.setScale(4), now);
        order = orderRepository.saveAndFlush(order);
        orderEventRepository.saveAndFlush(new OrderEvent(order, null, PENDING, ORDER_PLACED,
                "Resources reserved; order is pending and has not been executed", now));

        if (idempotencyKey != null) {
            IdempotencyRecord record = new IdempotencyRecord(account, idempotencyKey, fingerprint, now);
            record.complete(order);
            idempotencyRepository.saveAndFlush(record);
        }
        auditService.record(user.getEmail(), "ORDER_PLACED", "ORDER", order.getId(),
                "{\"symbol\":\"" + instrument.getSymbol() + "\",\"side\":\"" + side
                        + "\",\"mode\":\"" + tradingMode + "\",\"quantity\":" + request.quantity() + "}");
        return OrderPlacementResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderPreviewResponse previewOrder(String authenticatedEmail, OrderPlacementRequest request) {
        Instant now = Instant.now();
        User user = userRepository.findByEmail(authenticatedEmail.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> notFound("Authenticated user was not found"));
        if (!"ACTIVE".equals(user.getStatus())) throw forbidden("User account is not active");
        TradingAccount account = accountRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> notFound("Trading account was not found"));
        List<String> errors = new ArrayList<>();
        String exchange = normalized(request.exchange());
        String symbol = normalized(request.symbol());
        String side = normalized(request.side());
        String type = normalized(request.orderType());
        String mode = normalized(request.tradingMode());
        if (!"ACTIVE".equals(account.getStatus())) errors.add("Trading account is not active");
        try {
            validateOrderFields(request, side, type, mode);
        } catch (ResponseStatusException exception) {
            errors.add(exception.getReason());
            return previewResult(false, errors, exchange, symbol, side, type, mode, request.quantity(),
                    null, null, null, null, List.of(), "UNAVAILABLE", null, sessionState(now), "UNKNOWN");
        }
        Instrument instrument = instrumentRepository.findByExchangeAndSymbol(exchange, symbol)
                .filter(Instrument::isTradable).orElse(null);
        if (instrument == null) {
            errors.add("Unsupported or non-tradable instrument");
            return previewResult(false, errors, exchange, symbol, side, type, mode, request.quantity(),
                    null, null, null, null, List.of(), "UNAVAILABLE", null, sessionState(now), "UNKNOWN");
        }
        if (!account.getCurrency().equals(instrument.getCurrency())) errors.add("Instrument currency does not match the trading account");

        MarketQuote quote = quoteRepository.findByInstrument_Id(instrument.getId()).orElse(null);
        String freshness = quote == null ? "UNAVAILABLE" : com.tradecore.market.MarketQuoteResponse.from(quote, now).dataStatus();
        Long age = quote == null || quote.getProviderUpdatedAt() == null ? null
                : Math.max(0, Duration.between(quote.getProviderUpdatedAt(), now).getSeconds());
        String marketStatus = quote == null ? "UNKNOWN" : quote.getMarketStatus();
        boolean quoteEligible = quote != null && quoteIsEligible(quote, now);
        if (!quoteEligible) errors.add(quote == null ? "A current market quote is required" : "Market quote is stale or unavailable");
        String session = sessionState(now);
        if ("MARKET".equals(type) && !"ELIGIBLE".equals(session)) errors.add("Market orders require an open regular market session");

        BigDecimal eligiblePrice = quoteEligible ? quote.getLastPrice() : null;
        BigDecimal pricingReference = quoteEligible ? referencePrice(request, side, type, quote) : null;
        BigDecimal estimatedValue = pricingReference == null ? null
                : pricingReference.multiply(BigDecimal.valueOf(request.quantity())).setScale(4, RoundingMode.CEILING);
        BigDecimal reservation = "BUY".equals(side) ? estimatedValue : BigDecimal.ZERO.setScale(4);
        Long sellable = null;
        if ("BUY".equals(side) && reservation != null && account.getAvailableBalance().compareTo(reservation) < 0) {
            errors.add("Insufficient available virtual funds");
        } else if ("SELL".equals(side)) {
            Position position = positionRepository.findByAccount_IdAndInstrument_IdAndTradingMode(
                    account.getId(), instrument.getId(), mode).orElse(null);
            sellable = position == null ? 0L : position.getQuantity() - position.getReservedQuantity();
            if (sellable < request.quantity()) errors.add(position == null
                    ? "No sellable position exists for this instrument and mode"
                    : "Insufficient sellable position quantity");
        }
        List<String> riskFailures = estimatedValue == null ? List.of()
                : riskLimitFailures(account, instrument, request.quantity(), estimatedValue, now);
        errors.addAll(riskFailures);
        return previewResult(errors.isEmpty(), errors, exchange, symbol, side, type, mode, request.quantity(),
                eligiblePrice, estimatedValue, reservation, sellable, riskFailures, freshness, age, session, marketStatus);
    }

    private static OrderPreviewResponse previewResult(boolean valid, List<String> errors, String exchange,
            String symbol, String side, String type, String mode, long quantity, BigDecimal eligiblePrice,
            BigDecimal estimatedValue, BigDecimal reservation, Long sellable, List<String> riskFailures,
            String freshness, Long age, String session, String marketStatus) {
        return new OrderPreviewResponse(valid, List.copyOf(errors), exchange, symbol, side, type, mode,
                quantity, eligiblePrice, estimatedValue, reservation, sellable, List.copyOf(riskFailures),
                freshness, age, session, marketStatus);
    }

    private String sessionState(Instant now) {
        return marketHoursPolicy.isRegularSession(now) ? "ELIGIBLE" : "CLOSED";
    }

    private static BigDecimal referencePrice(OrderPlacementRequest request, String side, String orderType,
            MarketQuote quote) {
        return switch (orderType) {
            case "LIMIT" -> request.limitPrice();
            case "STOP_MARKET" -> "BUY".equals(side)
                    ? quote.getLastPrice().max(request.triggerPrice()) : quote.getLastPrice();
            default -> quote.getLastPrice();
        };
    }

    @Transactional
    public OrderHistoryResponse modifyPendingOrder(String authenticatedEmail, java.util.UUID orderId,
            OrderModificationRequest request) {
        Instant now = Instant.now();
        // Match execution/cancellation lock order: order, then account, then position.
        TradingOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> notFound("Order was not found"));
        if (!order.getAccount().getUser().getEmail().equals(authenticatedEmail.trim().toLowerCase(Locale.ROOT))) {
            throw notFound("Order was not found");
        }
        if (!PENDING.equals(order.getStatus())) throw conflict("Only pending orders can be modified");
        if ("MARKET".equals(order.getOrderType())) {
            throw conflict("MARKET orders have no mutable fields");
        }
        if (request == null) throw badRequest("A modification request is required");
        if (request.quantity() == null && request.limitPrice() == null && request.triggerPrice() == null) {
            throw badRequest("At least one mutable order field is required");
        }

        long quantity = request.quantity() == null ? order.getRequestedQuantity() : request.quantity();
        BigDecimal limitPrice = order.getLimitPrice();
        BigDecimal triggerPrice = order.getTriggerPrice();
        if ("LIMIT".equals(order.getOrderType())) {
            if (request.triggerPrice() != null) throw badRequest("triggerPrice cannot be modified on a LIMIT order");
            if (request.limitPrice() != null) limitPrice = request.limitPrice();
        } else if ("STOP_MARKET".equals(order.getOrderType())) {
            if (request.limitPrice() != null) throw badRequest("limitPrice cannot be modified on a STOP_MARKET order");
            if (request.triggerPrice() != null) triggerPrice = request.triggerPrice();
        } else {
            throw conflict("Order type does not support modification");
        }

        OrderPlacementRequest fields = new OrderPlacementRequest(order.getInstrument().getExchange(),
                order.getInstrument().getSymbol(), order.getSide(), order.getOrderType(), order.getTradingMode(),
                quantity, limitPrice, triggerPrice);
        validateOrderFields(fields, order.getSide(), order.getOrderType(), order.getTradingMode());

        TradingAccount account = accountRepository.findByIdForUpdate(order.getAccount().getId())
                .orElseThrow(() -> notFound("Trading account was not found"));
        if (!"ACTIVE".equals(account.getStatus())) throw conflict("Trading account is not active");
        MarketQuote quote = quoteRepository.findByInstrument_Id(order.getInstrument().getId())
                .orElseThrow(() -> unprocessable("A current market quote is required"));
        validateQuote(quote, now);

        BigDecimal referencePrice = switch (order.getOrderType()) {
            case "LIMIT" -> limitPrice;
            case "STOP_MARKET" -> "BUY".equals(order.getSide())
                    ? quote.getLastPrice().max(triggerPrice)
                    : quote.getLastPrice();
            default -> throw conflict("Order type does not support modification");
        };
        BigDecimal riskValue = referencePrice.multiply(BigDecimal.valueOf(quantity))
                .setScale(4, RoundingMode.CEILING);
        validateRiskLimits(account, order.getInstrument(), quantity, riskValue, now);

        if (quantity == order.getRequestedQuantity()
                && samePrice(limitPrice, order.getLimitPrice())
                && samePrice(triggerPrice, order.getTriggerPrice())) {
            return OrderHistoryResponse.from(order);
        }

        BigDecimal newReservedAmount = "BUY".equals(order.getSide()) ? riskValue : BigDecimal.ZERO.setScale(4);
        BigDecimal reservationChange = newReservedAmount.subtract(order.getReservedAmount());
        Position sellPosition = null;
        long quantityChange = quantity - order.getRemainingQuantity();
        if ("BUY".equals(order.getSide())) {
            if (reservationChange.signum() > 0) {
                if (account.getAvailableBalance().compareTo(reservationChange) < 0) {
                    throw unprocessable("Insufficient available virtual funds for the modified order");
                }
                account.reserveFunds(reservationChange, now);
            } else if (reservationChange.signum() < 0) {
                account.releaseReservedFunds(reservationChange.negate(), now);
            }
        } else {
            sellPosition = positionRepository.findForUpdate(account.getId(), order.getInstrument().getId(),
                            order.getTradingMode())
                    .orElseThrow(() -> conflict("Reserved sell position was not found"));
            if (quantityChange > 0) {
                if (sellPosition.getQuantity() - sellPosition.getReservedQuantity() < quantityChange) {
                    throw unprocessable("Insufficient sellable position quantity for the modified order");
                }
                sellPosition.reserve(quantityChange, now);
            } else if (quantityChange < 0) {
                sellPosition.releaseReservation(-quantityChange, now);
            }
        }

        long oldQuantity = order.getRequestedQuantity();
        BigDecimal oldLimitPrice = order.getLimitPrice();
        BigDecimal oldTriggerPrice = order.getTriggerPrice();
        order.modifyPending(quantity, limitPrice, triggerPrice, newReservedAmount, now);
        orderRepository.saveAndFlush(order);
        String reason = "Order modified: quantity " + oldQuantity + " -> " + quantity
                + ", limitPrice " + oldLimitPrice + " -> " + limitPrice
                + ", triggerPrice " + oldTriggerPrice + " -> " + triggerPrice;
        orderEventRepository.saveAndFlush(new OrderEvent(order, PENDING, PENDING, "ORDER_MODIFIED", reason, now));
        auditService.record(authenticatedEmail, "ORDER_MODIFIED", "ORDER", order.getId(),
                "{\"quantity\":" + quantity + "}");
        return OrderHistoryResponse.from(order);
    }

    private static boolean samePrice(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private static void validateOrderFields(OrderPlacementRequest request, String side,
            String orderType, String tradingMode) {
        if (request.quantity() <= 0) {
            throw badRequest("quantity must be greater than zero");
        }
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw badRequest("side must be BUY or SELL");
        }
        if (!"MARKET".equals(orderType) && !"LIMIT".equals(orderType) && !"STOP_MARKET".equals(orderType)) {
            throw badRequest("orderType must be MARKET, LIMIT or STOP_MARKET");
        }
        if (!"DELIVERY".equals(tradingMode) && !"INTRADAY".equals(tradingMode)) {
            throw badRequest("tradingMode must be DELIVERY or INTRADAY");
        }
        if ("MARKET".equals(orderType)
                && (request.limitPrice() != null || request.triggerPrice() != null)) {
            throw badRequest("limitPrice and triggerPrice must be omitted for MARKET orders");
        }
        if ("LIMIT".equals(orderType)) {
            if (request.triggerPrice() != null) throw badRequest("triggerPrice must be omitted for LIMIT orders");
            BigDecimal price = request.limitPrice();
            if (price == null || price.signum() <= 0) {
                throw badRequest("A LIMIT order requires a positive limitPrice");
            }
            try {
                if (price.setScale(6, RoundingMode.UNNECESSARY).precision() > 19) {
                    throw badRequest("limitPrice exceeds the supported precision");
                }
            } catch (ArithmeticException exception) {
                throw badRequest("limitPrice supports at most six decimal places");
            }
        }
        if ("STOP_MARKET".equals(orderType)) {
            if (request.limitPrice() != null) throw badRequest("limitPrice must be omitted for STOP_MARKET orders");
            BigDecimal trigger = request.triggerPrice();
            if (trigger == null || trigger.signum() <= 0) {
                throw badRequest("A STOP_MARKET order requires a positive triggerPrice");
            }
            try {
                if (trigger.setScale(6, RoundingMode.UNNECESSARY).precision() > 19) {
                    throw badRequest("triggerPrice exceeds the supported precision");
                }
            } catch (ArithmeticException exception) {
                throw badRequest("triggerPrice supports at most six decimal places");
            }
        }
    }

    private static void validateQuote(MarketQuote quote, Instant now) {
        if (!quoteIsEligible(quote, now)) throw unprocessable("Market quote is stale or unavailable");
    }

    private static boolean quoteIsEligible(MarketQuote quote, Instant now) {
        Instant updatedAt = quote.getProviderUpdatedAt();
        return "LIVE".equals(quote.getDataStatus()) && quote.getReceivedAt() != null && updatedAt != null
                && !updatedAt.isBefore(now.minus(MAX_QUOTE_AGE)) && !updatedAt.isAfter(now)
                && quote.getLastPrice() != null && quote.getLastPrice().signum() > 0;
    }

    private void validateRiskLimits(TradingAccount account, Instrument instrument, long quantity,
            BigDecimal orderValue, Instant now) {
        List<String> failures = riskLimitFailures(account, instrument, quantity, orderValue, now);
        if (!failures.isEmpty()) throw unprocessable(failures.get(0));
    }

    private List<String> riskLimitFailures(TradingAccount account, Instrument instrument, long quantity,
            BigDecimal orderValue, Instant now) {
        List<String> failures = new ArrayList<>();
        for (RiskLimit risk : riskLimitRepository.findApplicable(account.getId(), instrument.getId(), now)) {
            String type = normalized(risk.getLimitType());
            switch (type) {
                case "TRADING_DISABLED", "INSTRUMENT_BLOCKED" ->
                        failures.add("Trading is restricted by an active risk limit");
                case "MAX_ORDER_QUANTITY" -> {
                    if (BigDecimal.valueOf(quantity).compareTo(risk.getLimitValue()) > 0) {
                        failures.add("Order quantity exceeds the active risk limit");
                    }
                }
                case "MAX_ORDER_AMOUNT", "MAX_ORDER_VALUE" -> {
                    if (orderValue.compareTo(risk.getLimitValue()) > 0) {
                        failures.add("Order value exceeds the active risk limit");
                    }
                }
                default -> failures.add("An unsupported active risk limit prevents order placement");
            }
        }
        return List.copyOf(failures);
    }

    private static String normalizeIdempotencyKey(String key) {
        if (key == null) return null;
        String normalized = key.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw badRequest("Idempotency-Key must contain 1 to 160 characters");
        }
        return normalized;
    }

    private static String requestFingerprint(String exchange, String symbol, String side, String orderType,
            String tradingMode, long quantity, BigDecimal limitPrice, BigDecimal triggerPrice) {
        String canonical = String.join("|", exchange, symbol, side, orderType, tradingMode,
                Long.toString(quantity), limitPrice == null ? "" : limitPrice.stripTrailingZeros().toPlainString(),
                triggerPrice == null ? "" : triggerPrice.stripTrailingZeros().toPlainString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static BigDecimal normalizedPrice(BigDecimal price) {
        return price == null ? null : price.stripTrailingZeros();
    }

    private static String normalized(String value) { return value.trim().toUpperCase(Locale.ROOT); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private static ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException unprocessable(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
}
