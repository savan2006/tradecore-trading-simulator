package com.tradecore.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID accountId, String status, String currency, BigDecimal availableBalance,
        BigDecimal reservedBalance, Instant createdAt) {
    static AccountResponse from(TradingAccount account) {
        return new AccountResponse(account.getId(), account.getStatus(), account.getCurrency(),
                account.getAvailableBalance(), account.getReservedBalance(), account.getCreatedAt());
    }
}
