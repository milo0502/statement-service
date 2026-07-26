package com.example.statement_service.service;

import java.time.LocalDate;
import java.util.Optional;

import com.example.statement_service.domain.Statement;
import com.example.statement_service.observability.StatementMetrics;
import com.example.statement_service.persistence.StatementRepository;
import com.example.statement_service.storage.OrphanedS3ObjectCandidate;
import com.example.statement_service.storage.OrphanedS3ObjectCleanupService;
import com.example.statement_service.storage.S3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementServiceOrphanCleanupTest {

    private StatementRepository statementRepository;
    private S3Client s3;
    private TransactionTemplate transactionTemplate;
    private StatementMetrics metrics;
    private OrphanedS3ObjectCleanupService orphanedCleanupService;
    private StatementService service;

    @BeforeEach
    void setUp() {
        statementRepository = mock(StatementRepository.class);
        s3 = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        transactionTemplate = mock(TransactionTemplate.class);
        metrics = mock(StatementMetrics.class);
        orphanedCleanupService = mock(OrphanedS3ObjectCleanupService.class);
        S3Properties s3Properties = new S3Properties(
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
        service = new StatementService(
                statementRepository,
                s3,
                presigner,
                s3Properties,
                transactionTemplate,
                metrics,
                orphanedCleanupService
        );
    }

    @Test
    void deletesUploadedObjectWhenDatabaseWriteFails() {
        RuntimeException databaseFailure = new RuntimeException("database write failed");
        givenUploadSucceedsAndMetadataSaveFails(databaseFailure);
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());

        assertThatThrownBy(() -> service.upload(
                "customer-1",
                "account-1",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                pdf()
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to upload statement")
                .hasCause(databaseFailure);

        ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().bucket()).isEqualTo("statements");
        assertThat(deleteCaptor.getValue().key())
                .startsWith("customer/customer-1/account/account-1/2026-01/")
                .endsWith(".pdf");
        verify(orphanedCleanupService, never()).recordFailedUploadCleanup(any(), any());
        verify(metrics).uploadFailure();
    }

    @Test
    void recordsOrphanMetadataWhenCompensatingDeleteFails() {
        RuntimeException databaseFailure = new RuntimeException("database write failed");
        RuntimeException deleteFailure = new RuntimeException("delete denied");
        givenUploadSucceedsAndMetadataSaveFails(databaseFailure);
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(deleteFailure);

        assertThatThrownBy(() -> service.upload(
                "customer-1",
                "account-1",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                pdf()
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to upload statement")
                .hasCause(databaseFailure);

        ArgumentCaptor<OrphanedS3ObjectCandidate> candidateCaptor = ArgumentCaptor.forClass(OrphanedS3ObjectCandidate.class);
        verify(orphanedCleanupService).recordFailedUploadCleanup(candidateCaptor.capture(), any(RuntimeException.class));
        OrphanedS3ObjectCandidate candidate = candidateCaptor.getValue();
        assertThat(candidate.bucket()).isEqualTo("statements");
        assertThat(candidate.objectKey()).startsWith("customer/customer-1/account/account-1/2026-01/");
        assertThat(candidate.statementId()).isNotNull();
        assertThat(candidate.customerId()).isEqualTo("customer-1");
        assertThat(candidate.accountId()).isEqualTo("account-1");
        assertThat(candidate.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(candidate.periodEnd()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(candidate.sizeBytes()).isGreaterThan(0);
        assertThat(candidate.sha256()).hasSize(64);
    }

    private void givenUploadSucceedsAndMetadataSaveFails(RuntimeException databaseFailure) {
        when(statementRepository.findByCustomerIdAndAccountIdAndPeriodStartAndPeriodEndAndSha256(
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(Optional.empty());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        when(transactionTemplate.execute(org.mockito.ArgumentMatchers.<TransactionCallback<Statement>>any()))
                .thenThrow(databaseFailure);
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile(
                "file",
                "statement.pdf",
                "application/pdf",
                "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes()
        );
    }
}
