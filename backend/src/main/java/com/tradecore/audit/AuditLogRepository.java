package com.tradecore.audit;

import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;

/** Append-only persistence surface: audit records can be inserted but not updated or deleted through this repository. */
public interface AuditLogRepository extends Repository<AuditLog, UUID> {
    <S extends AuditLog> S save(S auditLog);

    @Query("select a from AuditLog a left join a.actor actor "
            + "where (:action is null or a.action = :action) "
            + "and (:actorSearch is null or (actor is not null and "
            + "(lower(actor.email) like concat('%', :actorSearch, '%') "
            + "or lower(actor.displayName) like concat('%', :actorSearch, '%')))) "
            + "and (:targetType is null or a.entityType = :targetType) "
            + "and (:outcome is null or a.metadata like concat('%\"outcome\":\"', :outcome, '\"%')) "
            + "and (:fromTime is null or a.occurredAt >= :fromTime) "
            + "and (:toTime is null or a.occurredAt <= :toTime)")
    Page<AuditLog> searchAuditLogs(@Param("action") String action,
            @Param("actorSearch") String actorSearch, @Param("targetType") String targetType,
            @Param("outcome") String outcome, @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime, Pageable pageable);
}
