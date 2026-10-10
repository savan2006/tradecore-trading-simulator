package com.tradecore.journal;

import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TradeJournalRequest(UUID orderId, @NotBlank @Size(max = 2000) String thesis,
        @Size(max = 80) String strategyTag, @Size(max = 2000) String wentWell,
        @Size(max = 2000) String wentWrong, @Size(max = 2000) String lessonLearned,
        @Min(1) @Max(5) Short rating) { }
