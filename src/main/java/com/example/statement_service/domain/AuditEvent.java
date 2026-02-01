package com.example.statement_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing an audit event in the system.
 * Used for logging user actions like uploads and link generations.
 */
@Entity
@Table(name = "audit_events")
@Getter
@Setter
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

    /**
     * Default constructor for JPA.
     */
    protected AuditEvent() {}

    /**
     * Constructs a new AuditEvent.
     *
     * @param id          the UUID of the event
     * @param customerId  the ID of the customer who performed the action
     * @param action      the action performed
     * @param statementId the ID of the statement involved (if any)
     * @param ip          the IP address of the user
     * @param userAgent   the User-Agent of the user's browser/client
     * @param createdAt   the timestamp when the event occurred
     */
    public AuditEvent(UUID id, String customerId, String action, UUID statementId, String ip, String userAgent, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.action = action;
        this.statementId = statementId;
        this.ip = ip;
        this.userAgent = userAgent;
        this.createdAt = createdAt;
    }

    /**
     * Safely processes a User-Agent string by ensuring it is not null and truncating it to a maximum length
     * of 512 characters if necessary.
     *
     * @param ua the original User-Agent string to process; may be null
     * @return the processed User-Agent string, truncated to 512 characters if it exceeds that length,
     *         or null if the input was null
     */
    private static String userAgentSafe(final String ua) {
        if (ua == null) return null;
        return ua.length() <= 512 ? ua : ua.substring(0, 512);
    }
}
