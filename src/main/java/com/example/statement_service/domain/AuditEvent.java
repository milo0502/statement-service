package com.example.statement_service.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    private UUID id;

    @Column(name = "customer_id", length = 128)
    private String customerId;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "statement_id")
    private UUID statementId;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEvent() {}

    public AuditEvent(UUID id, String customerId, String action, UUID statementId, String ip, String userAgent, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.action = action;
        this.statementId = statementId;
        this.ip = ip;
        this.userAgent = userAgent;
        this.createdAt = createdAt;
    }
}
