package com.tradecore.audit;

import com.tradecore.identity.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log", indexes = @Index(name = "ix_audit_actor_time", columnList = "actor_user_id,occurred_at"))
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_user_id") private User actor;
    @Column(nullable = false, length = 64) private String action;
    @Column(name = "entity_type", nullable = false, length = 48) private String entityType;
    @Column(name = "entity_id") private UUID entityId;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(columnDefinition = "text") private String metadata;
    protected AuditLog() {}
}
