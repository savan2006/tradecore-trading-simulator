package com.tradecore.watchlist;

import com.tradecore.identity.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "watchlist", uniqueConstraints = @UniqueConstraint(name = "uq_watchlist_user_name", columnNames = {"user_id", "name"}))
public class Watchlist {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(nullable = false, length = 80) private String name;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected Watchlist() {}
}
