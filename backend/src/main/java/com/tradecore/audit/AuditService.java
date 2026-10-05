package com.tradecore.audit;

import com.tradecore.identity.User;
import com.tradecore.identity.UserRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Best-effort append-only audit writer isolated from the transaction for the action being recorded. */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final UserRepository users;
    private final AuditLogRepository audits;
    private final TransactionTemplate isolatedTransaction;

    public AuditService(UserRepository users, AuditLogRepository audits, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.audits = audits;
        this.isolatedTransaction = new TransactionTemplate(transactionManager);
        this.isolatedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String actorEmail, String action, String targetType, UUID targetId, String metadata) {
        String normalizedActor = actorEmail == null || actorEmail.isBlank()
                ? null : actorEmail.trim().toLowerCase(Locale.ROOT);
        Instant occurredAt = Instant.now();
        String auditMetadata = metadata == null || metadata.isBlank()
                ? "{\"outcome\":\"SUCCESS\"}"
                : "{\"outcome\":\"SUCCESS\",\"details\":" + metadata + "}";
        Runnable write = () -> writeSafely(normalizedActor, action, targetType, targetId, occurredAt, auditMetadata);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { write.run(); }
            });
        } else {
            write.run();
        }
    }

    private void writeSafely(String actorEmail, String action, String targetType, UUID targetId,
            Instant occurredAt, String metadata) {
        try {
            isolatedTransaction.executeWithoutResult(status -> {
                User actor = actorEmail == null ? null : users.findByEmail(actorEmail).orElse(null);
                audits.save(new AuditLog(actor, action, targetType, targetId, occurredAt, metadata));
            });
        } catch (RuntimeException failure) {
            // Audit outages must never change the action's already-committed business result.
            log.warn("Could not persist audit entry for action {}", action);
        }
    }
}
