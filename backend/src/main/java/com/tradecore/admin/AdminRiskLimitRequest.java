package com.tradecore.admin;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminRiskLimitRequest(
        @NotBlank @Size(max = 16) String scope,
        @NotBlank @Size(max = 32) String limitType,
        UUID accountId,
        @Size(max = 16) String exchange,
        @Size(max = 32) String symbol,
        @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal configuredValue,
        Boolean enabled,
        Instant effectiveFrom,
        Instant effectiveUntil) {}
