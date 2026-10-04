package com.tradecore.watchlist;

import com.tradecore.market.Instrument;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "watchlist_item", uniqueConstraints = @UniqueConstraint(name = "uq_watchlist_item_instrument", columnNames = {"watchlist_id", "instrument_id"}), indexes = @Index(name = "ix_watchlist_item_order", columnList = "watchlist_id,sort_order"))
public class WatchlistItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "watchlist_id", nullable = false) private Watchlist watchlist;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "instrument_id", nullable = false) private Instrument instrument;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected WatchlistItem() {}

    public WatchlistItem(Watchlist watchlist, Instrument instrument, int sortOrder, Instant now) {
        this.watchlist = watchlist; this.instrument = instrument; this.sortOrder = sortOrder; this.createdAt = now;
    }
    public UUID getId() { return id; }
    public Watchlist getWatchlist() { return watchlist; }
    public Instrument getInstrument() { return instrument; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
}
