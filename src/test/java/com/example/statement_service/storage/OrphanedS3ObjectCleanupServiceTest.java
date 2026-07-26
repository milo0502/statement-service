package com.example.statement_service.storage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.statement_service.domain.OrphanedS3Object;
import com.example.statement_service.domain.OrphanedS3ObjectStatus;
import com.example.statement_service.persistence.OrphanedS3ObjectRepository;
import com.example.statement_service.persistence.StatementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrphanedS3ObjectCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");

    private S3Client s3;
    private StatementRepository statementRepository;
    private OrphanedS3ObjectRepository orphanedRepository;
    private OrphanedS3ObjectCleanupService service;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        statementRepository = mock(StatementRepository.class);
        orphanedRepository = mock(OrphanedS3ObjectRepository.class);
        service = new OrphanedS3ObjectCleanupService(
                s3,
                s3Properties(),
                statementRepository,
                orphanedRepository,
                new OrphanedObjectCleanupProperties(true, 10, 900),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void recordsFailedUploadCleanupWithInvestigationMetadata() {
        when(orphanedRepository.findFirstByBucketAndObjectKeyAndStatusOrderByFirstDetectedAtDesc(
                eq("statements"),
                eq("customer/customer-1/account/account-1/2026-01/statement.pdf"),
                eq(OrphanedS3ObjectStatus.PENDING)
        )).thenReturn(Optional.empty());

        OrphanedS3ObjectCandidate candidate = new OrphanedS3ObjectCandidate(
                "statements",
                "customer/customer-1/account/account-1/2026-01/statement.pdf",
                UUID.randomUUID(),
                "customer-1",
                "account-1",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                128L,
                "a".repeat(64)
        );

        service.recordFailedUploadCleanup(candidate, new RuntimeException("delete failed"));

        ArgumentCaptor<OrphanedS3Object> captor = ArgumentCaptor.forClass(OrphanedS3Object.class);
        verify(orphanedRepository).save(captor.capture());
        OrphanedS3Object saved = captor.getValue();
        assertThat(saved.getBucket()).isEqualTo(candidate.bucket());
        assertThat(saved.getObjectKey()).isEqualTo(candidate.objectKey());
        assertThat(saved.getStatementId()).isEqualTo(candidate.statementId());
        assertThat(saved.getCustomerId()).isEqualTo(candidate.customerId());
        assertThat(saved.getAccountId()).isEqualTo(candidate.accountId());
        assertThat(saved.getPeriodStart()).isEqualTo(candidate.periodStart());
        assertThat(saved.getPeriodEnd()).isEqualTo(candidate.periodEnd());
        assertThat(saved.getSizeBytes()).isEqualTo(candidate.sizeBytes());
        assertThat(saved.getSha256()).isEqualTo(candidate.sha256());
        assertThat(saved.getReason()).isEqualTo("UPLOAD_METADATA_SAVE_FAILED");
        assertThat(saved.getStatus()).isEqualTo(OrphanedS3ObjectStatus.PENDING);
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getLastError()).contains("delete failed");
    }

    @Test
    void deletesPendingRecordedObjectsAndMarksResolved() {
        OrphanedS3Object orphaned = OrphanedS3Object.pendingUploadCleanup(
                new OrphanedS3ObjectCandidate(
                        "statements",
                        "customer/customer-1/account/account-1/2026-01/statement.pdf",
                        UUID.randomUUID(),
                        "customer-1",
                        "account-1",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 31),
                        128L,
                        "a".repeat(64)
                ),
                "delete failed",
                NOW.minusSeconds(60)
        );
        when(orphanedRepository.findByStatusOrderByFirstDetectedAtAsc(eq(OrphanedS3ObjectStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(orphaned));
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(List.of())
                .build());

        OrphanedS3ObjectCleanupService.CleanupResult result = service.reconcile();

        assertThat(result.recordedObjectsProcessed()).isEqualTo(1);
        assertThat(orphaned.getStatus()).isEqualTo(OrphanedS3ObjectStatus.RESOLVED);
        assertThat(orphaned.getResolvedAt()).isEqualTo(NOW);
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
        verify(orphanedRepository).save(orphaned);
    }

    @Test
    void scansForOldUntrackedObjectsAndDeletesThem() {
        String objectKey = "customer/customer-2/account/account-2/2026-01/orphan.pdf";
        when(orphanedRepository.findByStatusOrderByFirstDetectedAtAsc(eq(OrphanedS3ObjectStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of());
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder()
                        .key(objectKey)
                        .size(256L)
                        .lastModified(NOW.minusSeconds(1200))
                        .build())
                .build());
        when(statementRepository.existsByObjectKey(objectKey)).thenReturn(false);
        when(orphanedRepository.findFirstByBucketAndObjectKeyAndStatusOrderByFirstDetectedAtDesc(
                eq("statements"),
                eq(objectKey),
                eq(OrphanedS3ObjectStatus.PENDING)
        )).thenReturn(Optional.empty());
        when(orphanedRepository.save(any(OrphanedS3Object.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());

        OrphanedS3ObjectCleanupService.CleanupResult result = service.reconcile();

        assertThat(result.scannedObjectsProcessed()).isEqualTo(1);
        ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().bucket()).isEqualTo("statements");
        assertThat(deleteCaptor.getValue().key()).isEqualTo(objectKey);

        ArgumentCaptor<OrphanedS3Object> orphanedCaptor = ArgumentCaptor.forClass(OrphanedS3Object.class);
        verify(orphanedRepository, org.mockito.Mockito.atLeastOnce()).save(orphanedCaptor.capture());
        OrphanedS3Object saved = orphanedCaptor.getValue();
        assertThat(saved.getReason()).isEqualTo("S3_SCAN_UNTRACKED_OBJECT");
        assertThat(saved.getStatus()).isEqualTo(OrphanedS3ObjectStatus.RESOLVED);
        assertThat(saved.getSizeBytes()).isEqualTo(256L);
    }

    @Test
    void scanSkipsObjectsThatStillHaveStatementMetadata() {
        String objectKey = "customer/customer-3/account/account-3/2026-01/active.pdf";
        when(orphanedRepository.findByStatusOrderByFirstDetectedAtAsc(eq(OrphanedS3ObjectStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of());
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder()
                        .key(objectKey)
                        .size(256L)
                        .lastModified(NOW.minusSeconds(1200))
                        .build())
                .build());
        when(statementRepository.existsByObjectKey(objectKey)).thenReturn(true);

        OrphanedS3ObjectCleanupService.CleanupResult result = service.reconcile();

        assertThat(result.scannedObjectsProcessed()).isZero();
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(orphanedRepository, never()).save(any(OrphanedS3Object.class));
    }

    private S3Properties s3Properties() {
        return new S3Properties(
                "http://localhost:9000",
                "",
                "af-south-1",
                "access-key",
                "secret-key",
                "statements",
                2000,
                5000,
                10000,
                4000,
                3
        );
    }
}
