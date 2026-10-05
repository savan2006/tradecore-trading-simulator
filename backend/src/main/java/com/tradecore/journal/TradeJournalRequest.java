package com.tradecore.journal;

import java.util.UUID;

public record TradeJournalRequest(UUID orderId, String thesis, String strategyTag,
        String wentWell, String wentWrong, String lessonLearned, Short rating) { }
