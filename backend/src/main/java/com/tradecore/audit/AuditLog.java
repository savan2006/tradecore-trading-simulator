package com.tradecore.audit;

import com.tradecore.identity.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log", indexes = @Index(name = "ix_audit_actor_time", columnList = "actor_user_id,occurred_at"))
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(updatable = false) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_user_id", updatable = false) private User actor;
    @Column(nullable = false, length = 64, updatable = false) private String action;
    @Column(name = "entity_type", nullable = false, length = 48, updatable = false) private String entityType;
    @Column(name = "entity_id", updatable = false) private UUID entityId;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;
    @Column(columnDefinition = "text", updatable = false) private String metadata;
    protected AuditLog() {}

    public UUID getId() { return id; }
    public User getActor() { return actor; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getMetadata() { return metadata; }

    public AuditLog(User actor, String action, String entityType, UUID entityId, Instant occurredAt, String metadata) {
        this.actor = actor;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.occurredAt = occurredAt;
        this.metadata = metadata;
    }
}
