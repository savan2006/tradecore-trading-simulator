package com.tradecore.admin;

import com.tradecore.account.TradingAccount;
import com.tradecore.account.TradingAccountRepository;
import com.tradecore.market.Instrument;
import com.tradecore.market.InstrumentRepository;
import com.tradecore.risk.RiskLimit;
import com.tradecore.risk.RiskLimitRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminRiskLimitService {
    private static final Set<String> SCOPES = Set.of("GLOBAL", "ACCOUNT", "INSTRUMENT");
    private static final Set<String> LIMIT_TYPES = Set.of("TRADING_DISABLED", "INSTRUMENT_BLOCKED",
            "MAX_ORDER_QUANTITY", "MAX_ORDER_AMOUNT", "MAX_ORDER_VALUE");
    private final RiskLimitRepository limits;
    private final TradingAccountRepository accounts;
    private final InstrumentRepository instruments;

    public AdminRiskLimitService(RiskLimitRepository limits, TradingAccountRepository accounts,
            InstrumentRepository instruments) {
        this.limits = limits;
        this.accounts = accounts;
        this.instruments = instruments;
    }

    @Transactional(readOnly = true)
    public List<AdminRiskLimitResponse> list() {
        Instant now = Instant.now();
        return limits.findAllForAdmin().stream().map(limit -> AdminRiskLimitResponse.from(limit, now)).toList();
    }

    @Transactional
    public AdminRiskLimitResponse create(AdminRiskLimitRequest request) {
        ValidatedConfiguration validated = validate(request, null);
        boolean enabled = request.enabled() == null || request.enabled();
        ensureNoOverlap(validated, request.effectiveFrom(), request.effectiveUntil(), enabled, null);
        RiskLimit limit = new RiskLimit(validated.account(), validated.instrument(), validated.scope(),
                validated.limitType(), request.configuredValue(), enabled, request.effectiveFrom(),
                request.effectiveUntil(), Instant.now());
        return AdminRiskLimitResponse.from(limits.saveAndFlush(limit), Instant.now());
    }

    @Transactional
    public AdminRiskLimitResponse update(UUID id, AdminRiskLimitRequest request) {
        RiskLimit limit = find(id);
        ValidatedConfiguration validated = validate(request, id);
        boolean enabled = request.enabled() == null ? limit.isEnabled() : request.enabled();
        ensureNoOverlap(validated, request.effectiveFrom(), request.effectiveUntil(), enabled, id);
        limit.configure(validated.account(), validated.instrument(), validated.scope(), validated.limitType(),
                request.configuredValue(), enabled, request.effectiveFrom(), request.effectiveUntil());
        return AdminRiskLimitResponse.from(limits.saveAndFlush(limit), Instant.now());
    }

    @Transactional
    public AdminRiskLimitResponse activate(UUID id) {
        RiskLimit limit = find(id);
        if (!limit.isEnabled()) {
            ValidatedConfiguration validated = new ValidatedConfiguration(limit.getAccount(), limit.getInstrument(),
                    limit.getScope(), limit.getLimitType());
            ensureNoOverlap(validated, limit.getEffectiveFrom(), limit.getEffectiveUntil(), true, id);
            limit.setEnabled(true);
            limits.saveAndFlush(limit);
        }
        return AdminRiskLimitResponse.from(limit, Instant.now());
    }

    @Transactional
    public AdminRiskLimitResponse deactivate(UUID id) {
        RiskLimit limit = find(id);
        if (limit.isEnabled()) {
            limit.setEnabled(false);
            limits.saveAndFlush(limit);
        }
        return AdminRiskLimitResponse.from(limit, Instant.now());
    }

    private ValidatedConfiguration validate(AdminRiskLimitRequest request, UUID ignoredId) {
        String scope = normalize(request.scope());
        String type = normalize(request.limitType());
        if (!SCOPES.contains(scope)) badRequest("scope must be GLOBAL, ACCOUNT, or INSTRUMENT");
        if (!LIMIT_TYPES.contains(type)) badRequest("limitType is not supported by order validation");
        if (request.configuredValue() == null || request.configuredValue().signum() <= 0) {
            badRequest("configuredValue must be positive");
        }
        if (request.effectiveFrom() != null && request.effectiveUntil() != null
                && !request.effectiveUntil().isAfter(request.effectiveFrom())) {
            badRequest("effectiveUntil must be after effectiveFrom");
        }

        TradingAccount account = null;
        Instrument instrument = null;
        switch (scope) {
            case "GLOBAL" -> {
                if (request.accountId() != null || hasInstrument(request)) {
                    badRequest("GLOBAL limits cannot target an account or instrument");
                }
            }
            case "ACCOUNT" -> {
                if (request.accountId() == null) badRequest("accountId is required for ACCOUNT limits");
                if (hasInstrument(request)) badRequest("ACCOUNT limits cannot target an instrument");
                account = accounts.findById(request.accountId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Trading account was not found"));
            }
            case "INSTRUMENT" -> {
                if (request.accountId() != null) badRequest("INSTRUMENT limits cannot target an account");
                if (request.symbol() == null || request.symbol().isBlank()) {
                    badRequest("symbol is required for INSTRUMENT limits");
                }
                String exchange = request.exchange() == null || request.exchange().isBlank()
                        ? "NSE" : request.exchange().trim().toUpperCase(Locale.ROOT);
                String symbol = request.symbol().trim().toUpperCase(Locale.ROOT);
                instrument = instruments.findByExchangeAndSymbol(exchange, symbol)
                        .filter(Instrument::isTradable)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Instrument must be a supported tradable instrument"));
            }
            default -> throw new IllegalStateException("Validated scope was not handled");
        }
        return new ValidatedConfiguration(account, instrument, scope, type);
    }

    private void ensureNoOverlap(ValidatedConfiguration configuration, java.time.Instant from,
            java.time.Instant until, boolean enabled, UUID excludeId) {
        if (enabled && limits.existsOverlappingEnabled(configuration.scope(), configuration.limitType(),
                configuration.account() == null ? null : configuration.account().getId(),
                configuration.instrument() == null ? null : configuration.instrument().getId(), from, until, excludeId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An enabled limit of this type already overlaps this scope and effective period");
        }
    }

    private RiskLimit find(UUID id) {
        return limits.findByIdForAdminUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Risk limit was not found"));
    }

    private static boolean hasInstrument(AdminRiskLimitRequest request) {
        return (request.exchange() != null && !request.exchange().isBlank())
                || (request.symbol() != null && !request.symbol().isBlank());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record ValidatedConfiguration(TradingAccount account, Instrument instrument, String scope, String limitType) {}
}
