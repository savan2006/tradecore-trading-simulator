package com.tradecore.alert;

import com.tradecore.identity.User;
import com.tradecore.market.Instrument;
import com.tradecore.watchlist.Watchlist;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "price_alert", indexes = {
        @Index(name = "ix_price_alert_active", columnList = "active,instrument_id"),
        @Index(name = "ix_price_alert_user", columnList = "user_id,created_at")
})
public class PriceAlert {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "watchlist_id", nullable = false) private Watchlist watchlist;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "instrument_id", nullable = false) private Instrument instrument;
    @Column(name = "condition", nullable = false, length = 8) private String condition;
    @Column(name = "target_price", nullable = false, precision = 19, scale = 6) private BigDecimal targetPrice;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "triggered_at") private Instant triggeredAt;
    @Version @Column(nullable = false) private long version;
    protected PriceAlert() {}

    public PriceAlert(User user, Watchlist watchlist, Instrument instrument, String condition,
            BigDecimal targetPrice, Instant now) {
        this.user = user; this.watchlist = watchlist; this.instrument = instrument;
        this.condition = condition; this.targetPrice = targetPrice; this.active = true; this.createdAt = now;
    }
    public UUID getId() { return id; }
    public User getUser() { return user; }
    public Watchlist getWatchlist() { return watchlist; }
    public Instrument getInstrument() { return instrument; }
    public String getCondition() { return condition; }
    public BigDecimal getTargetPrice() { return targetPrice; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getTriggeredAt() { return triggeredAt; }
    public void markTriggered(Instant now) { this.active = false; this.triggeredAt = now; }
}
