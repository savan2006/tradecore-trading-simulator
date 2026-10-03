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
    @Column(name = "received_at") private Instant receivedAt;
    @Column(name = "market_status", nullable = false, length = 24) private String marketStatus;
    @Column(name = "data_status", nullable = false, length = 24) private String dataStatus;
    protected MarketQuote() {}
}
