package com.tradecore.execution;

import java.util.List;

public record TradeHistoryPageResponse(List<TradeHistoryResponse> content, int page, int size,
        long totalElements, int totalPages, boolean hasNext) { }
