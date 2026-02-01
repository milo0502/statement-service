package com.example.statement_service.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.statement_service.domain.AuditEvent;
import com.example.statement_service.persistence.AuditEventRepository;

/**
 * Service for logging audit events.
 */
@Service
public class AuditService {

    private final AuditEventRepository repo;

    /**
     * Constructs a new AuditService.
     *
     * @param repo the audit event repository
     */
    public AuditService(AuditEventRepository repo) {
        this.repo = repo;
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
        repo.save(new AuditEvent(
                UUID.randomUUID(),
                customerId,
                action,
                statementId,
                ip,
                userAgent,
                Instant.now()
        ));
    }
}