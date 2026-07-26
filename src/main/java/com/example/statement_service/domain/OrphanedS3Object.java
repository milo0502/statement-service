package com.example.statement_service.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.example.statement_service.storage.OrphanedS3ObjectCandidate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "orphaned_s3_objects")
@Getter
public class OrphanedS3Object {

    @Id
    private UUID id;

    @Column(name = "bucket", nullable = false, length = 255)
    private String bucket;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "statement_id")
    private UUID statementId;

    @Column(name = "customer_id", length = 128)
    private String customerId;

    @Column(name = "account_id", length = 128)
    private String accountId;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "reason", nullable = false, length = 128)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrphanedS3ObjectStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "first_detected_at", nullable = false)
    private Instant firstDetectedAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "last_error")
    private String lastError;

    protected OrphanedS3Object() {
    }

    private OrphanedS3Object(OrphanedS3ObjectCandidate candidate, String reason, Instant detectedAt) {
        this.id = UUID.randomUUID();
        this.bucket = candidate.bucket();
        this.objectKey = candidate.objectKey();
        this.statementId = candidate.statementId();
        this.customerId = candidate.customerId();
        this.accountId = candidate.accountId();
        this.periodStart = candidate.periodStart();
        this.periodEnd = candidate.periodEnd();
        this.sizeBytes = candidate.sizeBytes();
        this.sha256 = candidate.sha256();
        this.reason = reason;
        this.status = OrphanedS3ObjectStatus.PENDING;
        this.firstDetectedAt = detectedAt;
    }

    public static OrphanedS3Object pendingUploadCleanup(
            OrphanedS3ObjectCandidate candidate,
            String error,
            Instant detectedAt
    ) {
        OrphanedS3Object object = new OrphanedS3Object(candidate, "UPLOAD_METADATA_SAVE_FAILED", detectedAt);
        object.markDeleteAttemptFailed(error, detectedAt);
        return object;
    }

    public static OrphanedS3Object discoveredByReconciliation(
            OrphanedS3ObjectCandidate candidate,
            Instant detectedAt
    ) {
        return new OrphanedS3Object(candidate, "S3_SCAN_UNTRACKED_OBJECT", detectedAt);
    }

    public void markDeleteAttemptFailed(String error, Instant attemptedAt) {
        this.status = OrphanedS3ObjectStatus.PENDING;
        this.attempts++;
        this.lastAttemptAt = attemptedAt;
        this.lastError = trim(error);
    }

    public void markResolved(Instant resolvedAt) {
        this.status = OrphanedS3ObjectStatus.RESOLVED;
        this.attempts++;
        this.lastAttemptAt = resolvedAt;
        this.resolvedAt = resolvedAt;
        this.lastError = null;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
