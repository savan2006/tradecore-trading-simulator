package com.tradecore.admin;

import com.tradecore.audit.AuditLog;
import com.tradecore.audit.AuditLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminAuditLogService {
    private static final int MAX_PAGE = 100_000;
    private static final int MAX_SIZE = 100;
    private static final Pattern OUTCOME = Pattern.compile("\\\"outcome\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");
    private final AuditLogRepository auditLogs;

    public AdminAuditLogService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @Transactional(readOnly = true)
    public AdminAuditLogPageResponse search(String action, String actor, String targetType, String outcome,
            Instant from, Instant to, int page, int size) {
        validatePage(page, size);
        if (from != null && to != null && from.isAfter(to)) throw badRequest("from must be <= to");
        String normalizedAction = normalize(action);
        String normalizedActor = actor == null || actor.isBlank() ? null : actor.trim().toLowerCase(Locale.ROOT);
        String normalizedTarget = normalize(targetType);
        String normalizedOutcome = normalize(outcome);
        if (normalizedAction != null && normalizedAction.length() > 64) throw badRequest("action is too long");
        if (normalizedActor != null && normalizedActor.length() > 320) throw badRequest("actor is too long");
        if (normalizedTarget != null && normalizedTarget.length() > 48) throw badRequest("targetType is too long");
        if (normalizedOutcome != null && !List.of("SUCCESS", "FAILURE", "UNKNOWN").contains(normalizedOutcome)) {
            throw badRequest("outcome must be SUCCESS, FAILURE, or UNKNOWN");
        }

        Page<AuditLog> results = auditLogs.searchAuditLogs(normalizedAction, normalizedActor,
                normalizedTarget, normalizedOutcome, from, to,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))));
        return new AdminAuditLogPageResponse(results.getContent().stream().map(AdminAuditLogService::toResponse).toList(),
                page, size, results.getTotalElements(), results.getTotalPages(), results.hasNext());
    }

    private static AdminAuditLogResponse toResponse(AuditLog audit) {
        String metadata = audit.getMetadata();
        Matcher matcher = metadata == null ? null : OUTCOME.matcher(metadata);
        String outcome = matcher != null && matcher.find() ? matcher.group(1) : "UNKNOWN";
        return new AdminAuditLogResponse(audit.getId(),
                audit.getActor() == null ? null : audit.getActor().getId(),
                audit.getActor() == null ? null : audit.getActor().getEmail(),
                audit.getAction(), audit.getEntityType(), audit.getEntityId(), audit.getOccurredAt(), outcome);
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || page > MAX_PAGE) throw badRequest("page must be between 0 and " + MAX_PAGE);
        if (size < 1 || size > MAX_SIZE) throw badRequest("size must be between 1 and " + MAX_SIZE);
    }
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
