package com.tradecore.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record OrderPlacementRequest(
        @NotBlank @Size(max = 16) String exchange,
        @NotBlank @Size(max = 32) String symbol,
        @NotBlank @Size(max = 8) String side,
        @NotBlank @Size(max = 12) String orderType,
        @NotBlank @Size(max = 12) String tradingMode,
        @Positive long quantity,
        BigDecimal limitPrice) {
}
