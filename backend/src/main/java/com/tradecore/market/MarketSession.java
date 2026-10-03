package com.tradecore.market;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "market_session")
public class MarketSession {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "trading_date", nullable = false, unique = true) private LocalDate tradingDate;
    @Column(name = "session_state", nullable = false, length = 24) private String sessionState;
    @Column(name = "opens_at") private Instant opensAt;
    @Column(name = "closes_at") private Instant closesAt;
    @Column(name = "square_off_at") private Instant squareOffAt;
    @Column(nullable = false) private boolean holiday;
    @Column(length = 240) private String description;
    protected MarketSession() {}
}
