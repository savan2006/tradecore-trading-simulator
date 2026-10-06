package com.tradecore.market;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tradecore.market-data.backfill")
public class HistoricalBackfillProperties {
    private int defaultMonths = 12;
    private int instrumentBatchSize = 5;

    public int getDefaultMonths() { return defaultMonths; }
    public void setDefaultMonths(int defaultMonths) { this.defaultMonths = defaultMonths; }
    public int getInstrumentBatchSize() { return instrumentBatchSize; }
    public void setInstrumentBatchSize(int instrumentBatchSize) { this.instrumentBatchSize = instrumentBatchSize; }
}
