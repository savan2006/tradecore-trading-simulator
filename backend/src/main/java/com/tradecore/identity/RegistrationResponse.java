package com.tradecore.identity;

import java.time.Instant;
import java.util.UUID;

public record RegistrationResponse(UUID userId, String email, String displayName, UUID accountId,
        String accountStatus, String currency, java.math.BigDecimal availableBalance,
        java.math.BigDecimal reservedBalance, Instant accountCreatedAt) {
}
