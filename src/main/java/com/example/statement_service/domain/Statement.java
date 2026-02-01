package com.example.statement_service.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Entity representing a bank statement.
 * Stores metadata and a reference to the actual file in S3.
 */
@Entity
@Table(name = "statements")
@Getter
@Setter
public class Statement {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 128)
    private String customerId;

    @Column(name = "account_id", nullable = false, length = 128)
    private String accountId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private StatementStatus status;

    /**
     * Default constructor for JPA.
     */
    protected Statement() {}

    /**
     * Constructs a new Statement.
     *
     * @param id          the UUID of the statement
     * @param customerId  the ID of the customer
     * @param accountId   the ID of the account
     * @param periodStart the start date of the statement period
     * @param periodEnd   the end date of the statement period
     * @param objectKey   the key (path) of the file in S3
     * @param contentType the MIME type of the file
     * @param sizeBytes   the size of the file in bytes
     * @param sha256      the SHA-256 hash of the file
     * @param uploadedAt  the timestamp when the statement was uploaded
     * @param status      the initial status of the statement
     */
    public Statement(
            UUID id,
            String customerId,
            String accountId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String objectKey,
            String contentType,
            long sizeBytes,
            String sha256,
            Instant uploadedAt,
            StatementStatus status
    ) {
        this.id = id;
        this.customerId = customerId;
        this.accountId = accountId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.uploadedAt = uploadedAt;
        this.status = status;
    }

    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getAccountId() { return accountId; }
    public LocalDate getPeriodStart() { return periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public String getObjectKey() { return objectKey; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public Instant getUploadedAt() { return uploadedAt; }
    public StatementStatus getStatus() { return status; }

    /**
     * Revokes the statement, changing its status to {@link StatementStatus#REVOKED}.
     */
    public void revoke() {
        this.status = StatementStatus.REVOKED;
    }
}