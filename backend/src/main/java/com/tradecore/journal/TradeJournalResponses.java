package com.tradecore.journal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record TradeJournalResponse(UUID id, UUID orderId, String exchange, String symbol, String side,
        String tradingMode, long quantity, String thesis, String strategyTag, String wentWell,
        String wentWrong, String lessonLearned, Short rating, Instant createdAt, Instant updatedAt) {
    static TradeJournalResponse from(TradeJournalEntry entry) {
        var order = entry.getOrder();
        return new TradeJournalResponse(entry.getId(), order.getId(), order.getInstrument().getExchange(),
                order.getInstrument().getSymbol(), order.getSide(), order.getTradingMode(),
                order.getExecutedQuantity(), entry.getThesis(), entry.getStrategyTag(), entry.getWentWell(),
                entry.getWentWrong(), entry.getLessonLearned(), entry.getRating(), entry.getCreatedAt(), entry.getUpdatedAt());
    }
}

record TradeJournalPageResponse(List<TradeJournalResponse> content, int page, int size,
        long totalElements, int totalPages, boolean hasNext) { }
