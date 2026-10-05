package com.tradecore.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record AdminAuditLogResponse(UUID id, UUID actorId, String actorEmail, String action,
        String targetType, UUID targetId, Instant occurredAt, String outcome) { }

record AdminAuditLogPageResponse(List<AdminAuditLogResponse> content, int page, int size,
        long totalElements, int totalPages, boolean hasNext) { }
