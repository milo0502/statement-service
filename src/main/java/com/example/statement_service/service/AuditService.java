package com.example.statement_service.service;

import java.time.Instant;
import java.util.UUID;

import com.example.statement_service.domain.AuditEvent;
import com.example.statement_service.persistence.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service for logging audit events.
 */
@Service
@EnableConfigurationProperties(AuditProperties.class)
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repo;
    private final AuditProperties properties;
    private final TransactionTemplate transactionTemplate;

    /**
     * Constructs a new AuditService.
     *
     * @param repo the audit event repository
     */
    @Autowired
    public AuditService(AuditEventRepository repo, AuditProperties properties, PlatformTransactionManager transactionManager) {
        this(repo, properties, requiresNew(transactionManager));
    }

    AuditService(AuditEventRepository repo, AuditProperties properties, TransactionTemplate transactionTemplate) {
        this.repo = repo;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Logs an audit event.
     *
     * @param customerId  the ID of the customer
     * @param action      the action being performed
     * @param statementId the ID of the statement involved
     * @param ip          the client's IP address
     * @param userAgent   the client's User-Agent
     */
    public void log(String customerId, String action, UUID statementId, String ip, String userAgent) {
        AuditEvent event = new AuditEvent(
                UUID.randomUUID(),
                customerId,
                action,
                statementId,
                ip,
                userAgent,
                Instant.now()
        );

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> repo.saveAndFlush(event));
                return;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                log.warn(
                        "Audit write failed action={} statementId={} customerId={} attempt={} maxAttempts={}",
                        action,
                        statementId,
                        customerId,
                        attempt,
                        properties.maxAttempts()
                );
                sleepBeforeRetry(attempt);
            }
        }

        if (properties.required()) {
            throw new AuditLoggingException(
                    "Audit log write failed after " + properties.maxAttempts() + " attempt(s)",
                    lastFailure
            );
        }

        log.error(
                "Audit write dropped after retries action={} statementId={} customerId={} maxAttempts={}",
                action,
                statementId,
                customerId,
                properties.maxAttempts(),
                lastFailure
        );
    }

    private void sleepBeforeRetry(int attempt) {
        if (attempt >= properties.maxAttempts() || properties.retryDelayMillis() <= 0) {
            return;
        }
        try {
            Thread.sleep(properties.retryDelayMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AuditLoggingException("Interrupted while retrying audit log write", interrupted);
        }
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
