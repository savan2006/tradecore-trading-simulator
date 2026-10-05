package com.tradecore.journal;

public record TradeJournalUpdateRequest(String thesis, String strategyTag,
        String wentWell, String wentWrong, String lessonLearned, Short rating) { }
