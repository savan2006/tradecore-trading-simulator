package com.tradecore.admin;

import java.time.LocalDate;
import java.util.List;

public record MarketDataCompletenessReport(
        int months,
        String description,
        int instrumentsTotal,
        int instrumentsWithAnyData,
        int instrumentsWithoutData,
        int instrumentsLikelyComplete,
        List<InstrumentCompleteness> instruments) {

    public record InstrumentCompleteness(
            String symbol,
            int candleCount,
            LocalDate firstDate,
            LocalDate lastDate,
            int weekdaysInRange,
            int candlesExpected,
            int missingWeekdaysCount,
            List<LocalDate> missingDates,
            boolean latestCandleIsStale) { }
}
