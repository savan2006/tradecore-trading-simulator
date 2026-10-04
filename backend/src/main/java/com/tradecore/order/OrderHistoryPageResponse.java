package com.tradecore.order;

import java.util.List;

public record OrderHistoryPageResponse(List<OrderHistoryResponse> content, int page, int size,
        long totalElements, int totalPages, boolean hasNext) { }
