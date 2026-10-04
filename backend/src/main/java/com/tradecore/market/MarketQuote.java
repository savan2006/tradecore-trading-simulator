package com.tradecore.market;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "market_quote")
public class MarketQuote {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "instrument_id", nullable = false, unique = true) private Instrument instrument;
    @Column(name = "last_price", precision = 19, scale = 6) private BigDecimal lastPrice;
    @Column(name = "previous_close", precision = 19, scale = 6) private BigDecimal previousClose;
    @Column(name = "open_price", precision = 19, scale = 6) private BigDecimal openPrice;
    @Column(name = "high_price", precision = 19, scale = 6) private BigDecimal highPrice;
    @Column(name = "low_price", precision = 19, scale = 6) private BigDecimal lowPrice;
    private Long volume;
    @Column(name = "bid_price", precision = 19, scale = 6) private BigDecimal bidPrice;
    @Column(name = "bid_quantity") private Long bidQuantity;
    @Column(name = "ask_price", precision = 19, scale = 6) private BigDecimal askPrice;
    @Column(name = "ask_quantity") private Long askQuantity;
    @Column(name = "market_at") private Instant marketAt;
    @Column(name = "provider_updated_at") private Instant providerUpdatedAt;
    @Column(name = "received_at") private Instant receivedAt;
    @Column(name = "market_status", nullable = false, length = 24) private String marketStatus;
    @Column(name = "data_status", nullable = false, length = 24) private String dataStatus;
    protected MarketQuote() {}

    public MarketQuote(Instrument instrument, MarketQuoteSnapshot snapshot, Instant receivedAt,
            String marketStatus, String dataStatus) {
        this.instrument = instrument;
        updateFrom(snapshot, receivedAt, marketStatus, dataStatus);
    }

    public void updateFrom(MarketQuoteSnapshot snapshot, Instant receivedAt,
            String marketStatus, String dataStatus) {
        this.lastPrice = snapshot.lastPrice();
        this.previousClose = snapshot.previousClose();
        this.openPrice = snapshot.open();
        this.highPrice = snapshot.high();
        this.lowPrice = snapshot.low();
        this.volume = snapshot.volume();
        this.marketAt = snapshot.tradingTimestamp();
        this.providerUpdatedAt = snapshot.dataUpdatedAt();
        this.receivedAt = receivedAt;
        this.marketStatus = marketStatus;
        this.dataStatus = dataStatus;
    }

    public Instrument getInstrument() { return instrument; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public BigDecimal getPreviousClose() { return previousClose; }
    public BigDecimal getOpenPrice() { return openPrice; }
    public BigDecimal getHighPrice() { return highPrice; }
    public BigDecimal getLowPrice() { return lowPrice; }
    public Long getVolume() { return volume; }
    public Instant getMarketAt() { return marketAt; }
    public Instant getProviderUpdatedAt() { return providerUpdatedAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getMarketStatus() { return marketStatus; }
    public String getDataStatus() { return dataStatus; }
}
