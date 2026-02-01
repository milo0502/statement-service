package com.example.statement_service.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.statement_service.domain.AuditEvent;

public record AuditEventResponse(
        UUID id,
        String customerId,
        String action,
        UUID statementId,
        String ip,
        String userAgent,
        Instant createdAt
) {
    public static AuditEventResponse from(AuditEvent e) {
        return new AuditEventResponse(
                e.getId(),
                e.getCustomerId(),
                e.getAction(),
                e.getStatementId(),
                e.getIp(),
                e.getUserAgent(),
                e.getCreatedAt()
        );
    }
}
