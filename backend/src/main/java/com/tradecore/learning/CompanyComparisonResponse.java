package com.tradecore.learning;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CompanyComparisonResponse(LocalDate from, LocalDate to,
        List<CompanyComparison> companies, List<IndexedCompanySeries> indexedSeries) {

    public record CompanyComparison(String symbol, String companyName, String sector, String businessType,
            String businessDescription, LocalDate startDate, BigDecimal startClose,
            LocalDate endDate, BigDecimal endClose, BigDecimal absoluteReturn,
            BigDecimal percentageReturn, BigDecimal annualizedVolatilityPercent,
            BigDecimal maximumDrawdownPercent, int numberOfTradingDays,
            LocalDate latestAvailableCandleDate, boolean insufficientHistoricalData, String dataNote) { }

    public record IndexedCompanySeries(String symbol, List<IndexedPoint> points) { }

    public record IndexedPoint(LocalDate date, BigDecimal indexedValue) { }
}
