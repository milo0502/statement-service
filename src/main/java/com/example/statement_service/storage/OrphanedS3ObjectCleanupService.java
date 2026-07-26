package com.example.statement_service.storage;

import java.time.Clock;
import java.time.Instant;

import com.example.statement_service.domain.OrphanedS3Object;
import com.example.statement_service.domain.OrphanedS3ObjectStatus;
import com.example.statement_service.persistence.OrphanedS3ObjectRepository;
import com.example.statement_service.persistence.StatementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

@Service
@EnableConfigurationProperties(OrphanedObjectCleanupProperties.class)
public class OrphanedS3ObjectCleanupService {

    private static final Logger log = LoggerFactory.getLogger(OrphanedS3ObjectCleanupService.class);
    private static final String SERVICE_OBJECT_PREFIX = "customer/";

    private final S3Client s3;
    private final S3Properties s3Properties;
    private final StatementRepository statementRepository;
    private final OrphanedS3ObjectRepository orphanedRepository;
    private final OrphanedObjectCleanupProperties cleanupProperties;
    private final Clock clock;

    @Autowired
    public OrphanedS3ObjectCleanupService(
            S3Client s3,
            S3Properties s3Properties,
            StatementRepository statementRepository,
            OrphanedS3ObjectRepository orphanedRepository,
            OrphanedObjectCleanupProperties cleanupProperties
    ) {
        this(s3, s3Properties, statementRepository, orphanedRepository, cleanupProperties, Clock.systemUTC());
    }

    OrphanedS3ObjectCleanupService(
            S3Client s3,
            S3Properties s3Properties,
            StatementRepository statementRepository,
            OrphanedS3ObjectRepository orphanedRepository,
            OrphanedObjectCleanupProperties cleanupProperties,
            Clock clock
    ) {
        this.s3 = s3;
        this.s3Properties = s3Properties;
        this.statementRepository = statementRepository;
        this.orphanedRepository = orphanedRepository;
        this.cleanupProperties = cleanupProperties;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${app.orphan-cleanup.initial-delay-millis:60000}",
            fixedDelayString = "${app.orphan-cleanup.fixed-delay-millis:3600000}"
    )
    public void reconcileScheduled() {
        if (cleanupProperties.enabled()) {
            reconcile();
        }
    }

    public CleanupResult reconcile() {
        int limit = cleanupProperties.batchSize();
        int recorded = reconcileRecordedObjects(limit);
        int scanned = recorded >= limit ? 0 : scanForUntrackedObjects(limit - recorded);
        return new CleanupResult(recorded, scanned);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedUploadCleanup(OrphanedS3ObjectCandidate candidate, RuntimeException cleanupFailure) {
        Instant now = clock.instant();
        try {
            var existing = orphanedRepository.findFirstByBucketAndObjectKeyAndStatusOrderByFirstDetectedAtDesc(
                    candidate.bucket(),
                    candidate.objectKey(),
                    OrphanedS3ObjectStatus.PENDING
            );
            OrphanedS3Object orphaned;
            if (existing.isPresent()) {
                orphaned = existing.get();
                orphaned.markDeleteAttemptFailed(cleanupFailure.toString(), now);
            } else {
                orphaned = OrphanedS3Object.pendingUploadCleanup(
                        candidate,
                        cleanupFailure.toString(),
                        now
                );
            }
            orphanedRepository.save(orphaned);
        } catch (RuntimeException recordFailure) {
            log.error(
                    "Failed to record orphaned S3 object bucket={} key={} statementId={} customerId={} accountId={} sha256={} cleanupError={}",
                    candidate.bucket(),
                    candidate.objectKey(),
                    candidate.statementId(),
                    candidate.customerId(),
                    candidate.accountId(),
                    candidate.sha256(),
                    cleanupFailure.toString(),
                    recordFailure
            );
        }
    }

    private int reconcileRecordedObjects(int limit) {
        var pending = orphanedRepository.findByStatusOrderByFirstDetectedAtAsc(
                OrphanedS3ObjectStatus.PENDING,
                PageRequest.of(0, limit)
        );

        int processed = 0;
        for (OrphanedS3Object orphaned : pending) {
            deleteAndUpdate(orphaned);
            processed++;
        }
        return processed;
    }

    private int scanForUntrackedObjects(int limit) {
        int processed = 0;
        String continuationToken = null;
        Instant cutoff = clock.instant().minus(cleanupProperties.minObjectAge());

        do {
            var request = ListObjectsV2Request.builder()
                    .bucket(s3Properties.bucket())
                    .prefix(SERVICE_OBJECT_PREFIX)
                    .maxKeys(Math.min(1000, Math.max(1, limit - processed)))
                    .continuationToken(continuationToken)
                    .build();
            var response = s3.listObjectsV2(request);

            for (S3Object object : response.contents()) {
                if (processed >= limit) {
                    break;
                }
                if (!isOldEnough(object, cutoff) || statementRepository.existsByObjectKey(object.key())) {
                    continue;
                }
                OrphanedS3Object orphaned = orphanedRepository
                        .findFirstByBucketAndObjectKeyAndStatusOrderByFirstDetectedAtDesc(
                                s3Properties.bucket(),
                                object.key(),
                                OrphanedS3ObjectStatus.PENDING
                        )
                        .orElseGet(() -> orphanedRepository.save(OrphanedS3Object.discoveredByReconciliation(
                                new OrphanedS3ObjectCandidate(
                                        s3Properties.bucket(),
                                        object.key(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        object.size(),
                                        null
                                ),
                                clock.instant()
                        )));
                deleteAndUpdate(orphaned);
                processed++;
            }

            continuationToken = response.nextContinuationToken();
        } while (processed < limit && continuationToken != null);

        return processed;
    }

    private boolean isOldEnough(S3Object object, Instant cutoff) {
        return object.lastModified() != null && !object.lastModified().isAfter(cutoff);
    }

    private void deleteAndUpdate(OrphanedS3Object orphaned) {
        Instant now = clock.instant();
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(orphaned.getBucket())
                    .key(orphaned.getObjectKey())
                    .build());
            orphaned.markResolved(now);
            orphanedRepository.save(orphaned);
        } catch (RuntimeException deleteFailure) {
            orphaned.markDeleteAttemptFailed(deleteFailure.toString(), now);
            orphanedRepository.save(orphaned);
            log.warn(
                    "Failed to delete orphaned S3 object bucket={} key={} orphanRecordId={} attempts={}",
                    orphaned.getBucket(),
                    orphaned.getObjectKey(),
                    orphaned.getId(),
                    orphaned.getAttempts()
            );
        }
    }

    public record CleanupResult(int recordedObjectsProcessed, int scannedObjectsProcessed) {
    }
}
