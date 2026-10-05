package com.tradecore.risk;

import java.math.BigDecimal;
import java.time.Instant;

record RiskLimitResponse(String scope, String limitType, BigDecimal configuredValue,
        String exchange, String symbol, BigDecimal currentUsage, BigDecimal remainingValue,
        Instant effectiveFrom, Instant effectiveUntil) {
    static RiskLimitResponse from(RiskLimit limit) {
        return new RiskLimitResponse(limit.getScope(), limit.getLimitType(), limit.getLimitValue(),
                limit.getInstrument() == null ? null : limit.getInstrument().getExchange(),
                limit.getInstrument() == null ? null : limit.getInstrument().getSymbol(),
                null, null, limit.getEffectiveFrom(), limit.getEffectiveUntil());
    }
}
