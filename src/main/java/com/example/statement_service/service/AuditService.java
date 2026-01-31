package com.example.statement_service.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.statement_service.domain.AuditEvent;
import com.example.statement_service.persistence.AuditEventRepository;

@Service
public class AuditService {

    private final AuditEventRepository repo;

    public AuditService(AuditEventRepository repo) {
        this.repo = repo;
    }

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