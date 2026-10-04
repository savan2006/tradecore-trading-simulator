package com.tradecore.alert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

public record PriceAlertRequest(@NotNull UUID watchlistId, @NotNull UUID instrumentId,
        @NotNull @Pattern(regexp = "(?i)ABOVE|BELOW") String condition,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal targetPrice) { }
