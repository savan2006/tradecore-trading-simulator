package com.tradecore.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record AdminOverviewResponse(long totalUsers, long activeTradingAccounts, long pendingOrders,
        long filledOrders, long cancelledOrders, long openPositions, long unreadNotifications,
        long supportedInstruments, Instant latestMarketDataRefreshAt,
        String latestMarketDataRefreshStatus) { }

record AdminUserResponse(UUID id, String email, String displayName, String role, String status,
        Instant createdAt) { }

record AdminUserPageResponse(List<AdminUserResponse> content, int page, int size,
        long totalElements, int totalPages, boolean hasNext) { }

record AdminOrderResponse(UUID orderId, String exchange, String symbol, String side,
        String orderType, String tradingMode, long requestedQuantity, long executedQuantity,
        long remainingQuantity, String status, Instant createdAt, Instant updatedAt) { }

record AdminOrderPageResponse(List<AdminOrderResponse> content, int page, int size,
        long totalElements, int totalPages, boolean hasNext) { }

record AdminMarketStatusResponse(boolean quoteRefreshEnabled, String quoteRefreshInterval,
        Instant lastQuoteRefreshAttemptAt, String lastQuoteRefreshOutcome,
        Instant lastSuccessfulQuoteRefreshAt, long quoteRefreshFailureCount,
        boolean candleRefreshEnabled, String candleRefreshCron, Instant lastCandleRefreshAttemptAt,
        String lastCandleRefreshOutcome, Instant lastSuccessfulCandleRefreshAt,
        long candleRefreshFailureCount, boolean orderExecutionEnabled, String orderExecutionInterval,
        String squareOffCheckInterval, Instant latestPersistedQuoteAt, List<JobRunStatus> jobs) { }
