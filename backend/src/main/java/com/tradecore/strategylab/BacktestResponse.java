package com.tradecore.strategylab;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacktestResponse(
        BacktestStrategy strategy,
        String symbol,
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal startingCapital,
        BigDecimal endingCapital,
        BigDecimal totalReturnPercent,
        BigDecimal realizedPnl,
        int numberOfTrades,
        int winningTrades,
        int losingTrades,
        BigDecimal winRatePercent,
        BigDecimal averageWinningTrade,
        BigDecimal averageLosingTrade,
        BigDecimal maximumDrawdownPercent,
        BigDecimal buyAndHoldReturnPercent,
        List<EquityPoint> equityCurve,
        List<SimulatedTrade> simulatedTrades,
        String executionConvention,
        String costTreatment) {

    public record EquityPoint(LocalDate date, BigDecimal equity) {}

    public record SimulatedTrade(
            LocalDate entryDate,
            BigDecimal entryPrice,
            LocalDate exitDate,
            BigDecimal exitPrice,
            long quantity,
            BigDecimal investedAmount,
            BigDecimal realizedResult,
            String status) {}
}
