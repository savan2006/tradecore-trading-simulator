package com.tradecore.admin;

import com.tradecore.risk.RiskLimit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminRiskLimitResponse(UUID id, UUID accountId, String accountEmail, String scope,
        String limitType, BigDecimal configuredValue, String exchange, String symbol, boolean enabled,
        boolean effectiveNow, Instant effectiveFrom, Instant effectiveUntil, Instant createdAt) {
    static AdminRiskLimitResponse from(RiskLimit limit, Instant now) {
        boolean effectiveNow = limit.isEnabled()
                && (limit.getEffectiveFrom() == null || !limit.getEffectiveFrom().isAfter(now))
                && (limit.getEffectiveUntil() == null || limit.getEffectiveUntil().isAfter(now));
        return new AdminRiskLimitResponse(limit.getId(),
                limit.getAccount() == null ? null : limit.getAccount().getId(),
                limit.getAccount() == null ? null : limit.getAccount().getUser().getEmail(),
                limit.getScope(), limit.getLimitType(), limit.getLimitValue(),
                limit.getInstrument() == null ? null : limit.getInstrument().getExchange(),
                limit.getInstrument() == null ? null : limit.getInstrument().getSymbol(),
                limit.isEnabled(), effectiveNow, limit.getEffectiveFrom(), limit.getEffectiveUntil(),
                limit.getCreatedAt());
    }
}
