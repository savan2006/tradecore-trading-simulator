package com.tradecore.market;

import java.time.Duration;
import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Scheduling controls for the existing market-data ingestion flow. */
@ConfigurationProperties(prefix = "tradecore.market-data.scheduling")
public class MarketDataRefreshProperties {

    private boolean quoteEnabled = true;
    private Duration quoteInterval = Duration.ofMinutes(5);
    private boolean candleEnabled = true;
    private String candleCron = "0 0 18 * * MON-FRI";
    private String timezone = "Asia/Kolkata";
    private LocalTime regularSessionOpen = LocalTime.of(9, 15);
    private LocalTime regularSessionClose = LocalTime.of(15, 30);

    public boolean isQuoteEnabled() { return quoteEnabled; }
    public void setQuoteEnabled(boolean quoteEnabled) { this.quoteEnabled = quoteEnabled; }
    public Duration getQuoteInterval() { return quoteInterval; }
    public void setQuoteInterval(Duration quoteInterval) { this.quoteInterval = quoteInterval; }
    public boolean isCandleEnabled() { return candleEnabled; }
    public void setCandleEnabled(boolean candleEnabled) { this.candleEnabled = candleEnabled; }
    public String getCandleCron() { return candleCron; }
    public void setCandleCron(String candleCron) { this.candleCron = candleCron; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public LocalTime getRegularSessionOpen() { return regularSessionOpen; }
    public void setRegularSessionOpen(LocalTime regularSessionOpen) { this.regularSessionOpen = regularSessionOpen; }
    public LocalTime getRegularSessionClose() { return regularSessionClose; }
    public void setRegularSessionClose(LocalTime regularSessionClose) { this.regularSessionClose = regularSessionClose; }
}
