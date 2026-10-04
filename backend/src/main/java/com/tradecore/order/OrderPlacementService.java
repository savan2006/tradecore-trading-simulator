package com.tradecore.order;

import com.tradecore.account.TradingAccount;
import com.tradecore.account.TradingAccountRepository;
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

    public OrderPlacementService(UserRepository userRepository, TradingAccountRepository accountRepository,
            InstrumentRepository instrumentRepository, MarketQuoteRepository quoteRepository,
            PositionRepository positionRepository, RiskLimitRepository riskLimitRepository,
            TradingOrderRepository orderRepository, OrderEventRepository orderEventRepository,
            IdempotencyRecordRepository idempotencyRepository, MarketHoursPolicy marketHoursPolicy) {
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
                request.quantity(), normalizedPrice(request.limitPrice()));
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
        if ("MARKET".equals(orderType)
                && (!marketHoursPolicy.isRegularSession(now) || !"OPEN".equals(quote.getMarketStatus()))) {
            throw unprocessable("Market orders require an open regular market session");
        }

        BigDecimal referencePrice = "LIMIT".equals(orderType) ? request.limitPrice() : quote.getLastPrice();
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
                request.quantity(), request.limitPrice(), "BUY".equals(side) ? reservation : BigDecimal.ZERO.setScale(4), now);
        order = orderRepository.saveAndFlush(order);
        orderEventRepository.saveAndFlush(new OrderEvent(order, null, PENDING, ORDER_PLACED,
                "Resources reserved; order is pending and has not been executed", now));

        if (idempotencyKey != null) {
            IdempotencyRecord record = new IdempotencyRecord(account, idempotencyKey, fingerprint, now);
            record.complete(order);
            idempotencyRepository.saveAndFlush(record);
        }
        return OrderPlacementResponse.from(order);
    }

    private static void validateOrderFields(OrderPlacementRequest request, String side,
            String orderType, String tradingMode) {
        if (request.quantity() <= 0) {
            throw badRequest("quantity must be greater than zero");
        }
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw badRequest("side must be BUY or SELL");
        }
        if (!"MARKET".equals(orderType) && !"LIMIT".equals(orderType)) {
            throw badRequest("orderType must be MARKET or LIMIT");
        }
        if (!"DELIVERY".equals(tradingMode) && !"INTRADAY".equals(tradingMode)) {
            throw badRequest("tradingMode must be DELIVERY or INTRADAY");
        }
        if ("MARKET".equals(orderType) && request.limitPrice() != null) {
            throw badRequest("limitPrice must be omitted for MARKET orders");
        }
        if ("LIMIT".equals(orderType)) {
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
    }

    private static void validateQuote(MarketQuote quote, Instant now) {
        Instant updatedAt = quote.getProviderUpdatedAt();
        if (!"LIVE".equals(quote.getDataStatus()) || quote.getReceivedAt() == null || updatedAt == null
                || updatedAt.isBefore(now.minus(MAX_QUOTE_AGE)) || updatedAt.isAfter(now)
                || quote.getLastPrice() == null || quote.getLastPrice().signum() <= 0) {
            throw unprocessable("Market quote is stale or unavailable");
        }
    }

    private void validateRiskLimits(TradingAccount account, Instrument instrument, long quantity,
            BigDecimal orderValue, Instant now) {
        for (RiskLimit risk : riskLimitRepository.findApplicable(account.getId(), instrument.getId(), now)) {
            String type = normalized(risk.getLimitType());
            switch (type) {
                case "TRADING_DISABLED", "INSTRUMENT_BLOCKED" ->
                        throw unprocessable("Trading is restricted by an active risk limit");
                case "MAX_ORDER_QUANTITY" -> {
                    if (BigDecimal.valueOf(quantity).compareTo(risk.getLimitValue()) > 0) {
                        throw unprocessable("Order quantity exceeds the active risk limit");
                    }
                }
                case "MAX_ORDER_AMOUNT", "MAX_ORDER_VALUE" -> {
                    if (orderValue.compareTo(risk.getLimitValue()) > 0) {
                        throw unprocessable("Order value exceeds the active risk limit");
                    }
                }
                default -> throw unprocessable("An unsupported active risk limit prevents order placement");
            }
        }
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
            String tradingMode, long quantity, BigDecimal limitPrice) {
        String canonical = String.join("|", exchange, symbol, side, orderType, tradingMode,
                Long.toString(quantity), limitPrice == null ? "" : limitPrice.stripTrailingZeros().toPlainString());
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
