package com.tradecore.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, unique = true, length = 320) private String email;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(nullable = false, length = 20) private String role;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    protected User() {}
}
