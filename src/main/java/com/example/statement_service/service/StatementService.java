package com.example.statement_service.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

import com.example.statement_service.domain.Statement;
import com.example.statement_service.domain.StatementStatus;
import com.example.statement_service.observability.StatementMetrics;
import com.example.statement_service.persistence.StatementRepository;
import com.example.statement_service.storage.OrphanedS3ObjectCandidate;
import com.example.statement_service.storage.OrphanedS3ObjectCleanupService;
import com.example.statement_service.storage.S3Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);
    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    private static final int MAX_METADATA_LENGTH = 128;
    private static final Pattern SAFE_METADATA_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@-]{0,127}");
    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,254}");
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final StatementRepository statementRepo;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Properties s3Props;
    private final TransactionTemplate transactionTemplate;
    private final StatementMetrics metrics;
    private final OrphanedS3ObjectCleanupService orphanedObjectCleanupService;

    public StatementService(
            StatementRepository statementRepo,
            S3Client s3,
            S3Presigner presigner,
            S3Properties s3Props,
            TransactionTemplate transactionTemplate,
            StatementMetrics metrics,
            OrphanedS3ObjectCleanupService orphanedObjectCleanupService
    ) {
        this.statementRepo = statementRepo;
        this.s3 = s3;
        this.presigner = presigner;
        this.s3Props = s3Props;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
        this.orphanedObjectCleanupService = orphanedObjectCleanupService;
    }

    public Statement upload(
            String customerId,
            String accountId,
            LocalDate periodStart,
            LocalDate periodEnd,
            MultipartFile pdf
    ) {
        try {
            validateUpload(customerId, accountId, periodStart, periodEnd, pdf);
            return uploadValidated(customerId, accountId, periodStart, periodEnd, pdf);
        } catch (BadRequestException e) {
            metrics.uploadFailure();
            throw e;
        } catch (Exception e) {
            metrics.uploadFailure();
            throw new RuntimeException("Failed to upload statement", e);
        }
    }

    private Statement uploadValidated(
            String customerId,
            String accountId,
            LocalDate periodStart,
            LocalDate periodEnd,
            MultipartFile pdf
    ) throws Exception {
        Path tmp = Files.createTempFile("statement-", ".pdf");
        try {
            copyUploadToTempFile(pdf, tmp);
            validatePdfSignature(tmp);

            String sha256 = sha256Hex(tmp);
            var existing = statementRepo.findByCustomerIdAndAccountIdAndPeriodStartAndPeriodEndAndSha256(
                    customerId, accountId, periodStart, periodEnd, sha256
            );
            if (existing.isPresent()) {
                metrics.uploadSuccess();
                return existing.get();
            }

            UUID id = UUID.randomUUID();
            String objectKey = "customer/%s/account/%s/%s/%s.pdf"
                    .formatted(customerId, accountId, periodStart.getYear() + "-" + String.format("%02d", periodStart.getMonthValue()), id);
            long size = Files.size(tmp);

            uploadToS3(tmp, objectKey);

            Statement statement = new Statement(
                    id, customerId, accountId, periodStart, periodEnd,
                    objectKey, "application/pdf", size, sha256,
                    Instant.now(), StatementStatus.ACTIVE
            );

            try {
                Statement saved = transactionTemplate.execute(status -> statementRepo.saveAndFlush(statement));
                metrics.uploadSuccess();
                return saved;
            } catch (DataIntegrityViolationException duplicateUploadRace) {
                cleanupUploadedObject(orphanCandidate(statement, objectKey));
                Statement existingStatement = statementRepo.findByCustomerIdAndAccountIdAndPeriodStartAndPeriodEndAndSha256(
                        customerId, accountId, periodStart, periodEnd, sha256
                ).orElseThrow(() -> duplicateUploadRace);
                metrics.uploadSuccess();
                return existingStatement;
            } catch (RuntimeException dbFailure) {
                cleanupUploadedObject(orphanCandidate(statement, objectKey));
                throw dbFailure;
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
        }
    }

    private void validateUpload(String customerId, String accountId, LocalDate periodStart, LocalDate periodEnd, MultipartFile pdf) {
        validateMetadata("customerId", customerId);
        validateMetadata("accountId", accountId);
        if (pdf == null || pdf.isEmpty()) {
            throw new BadRequestException("PDF file is required");
        }
        if (pdf.getSize() > MAX_UPLOAD_BYTES) {
            throw new BadRequestException("PDF file exceeds the 10MB upload limit");
        }
        validateFilename(pdf.getOriginalFilename());
        if (periodStart == null || periodEnd == null) {
            throw new BadRequestException("periodStart and periodEnd are required");
        }
        if (periodEnd.isBefore(periodStart)) {
            throw new BadRequestException("periodEnd must be on/after periodStart");
        }
        String contentType = (pdf.getContentType() == null) ? "" : pdf.getContentType();
        if (!contentType.equalsIgnoreCase("application/pdf")) {
            throw new BadRequestException("Only application/pdf is supported");
        }
    }

    private void validateMetadata(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        if (value.length() > MAX_METADATA_LENGTH || !SAFE_METADATA_VALUE.matcher(value).matches()) {
            throw new BadRequestException(field + " contains unsupported characters");
        }
    }

    private void validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BadRequestException("PDF filename is required");
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")
                || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                || !SAFE_FILENAME.matcher(filename).matches()) {
            throw new BadRequestException("PDF filename is not supported");
        }
    }

    private void copyUploadToTempFile(MultipartFile pdf, Path tmp) throws Exception {
        try (InputStream in = pdf.getInputStream(); var out = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_UPLOAD_BYTES) {
                    throw new BadRequestException("PDF file exceeds the 10MB upload limit");
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private void validatePdfSignature(Path file) throws Exception {
        byte[] header = new byte[PDF_SIGNATURE.length];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.read(header);
            if (read < PDF_SIGNATURE.length) {
                throw new BadRequestException("PDF file is missing a valid PDF signature");
            }
        }
        for (int i = 0; i < PDF_SIGNATURE.length; i++) {
            if (header[i] != PDF_SIGNATURE[i]) {
                throw new BadRequestException("PDF file is missing a valid PDF signature");
            }
        }
    }

    private void uploadToS3(Path file, String objectKey) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(s3Props.bucket())
                .key(objectKey)
                .contentType("application/pdf")
                .build();

        s3.putObject(put, RequestBody.fromFile(file));
    }

    private OrphanedS3ObjectCandidate orphanCandidate(Statement statement, String objectKey) {
        return new OrphanedS3ObjectCandidate(
                s3Props.bucket(),
                objectKey,
                statement.getId(),
                statement.getCustomerId(),
                statement.getAccountId(),
                statement.getPeriodStart(),
                statement.getPeriodEnd(),
                statement.getSizeBytes(),
                statement.getSha256()
        );
    }

    private void cleanupUploadedObject(OrphanedS3ObjectCandidate candidate) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(candidate.bucket())
                    .key(candidate.objectKey())
                    .build());
        } catch (RuntimeException cleanupFailure) {
            log.warn(
                    "Failed to clean up uploaded object after metadata persistence failure statementId={} bucket={} key={} customerId={} accountId={} sha256={}",
                    candidate.statementId(),
                    candidate.bucket(),
                    candidate.objectKey(),
                    candidate.customerId(),
                    candidate.accountId(),
                    candidate.sha256()
            );
            if (orphanedObjectCleanupService != null) {
                orphanedObjectCleanupService.recordFailedUploadCleanup(candidate, cleanupFailure);
            }
        }
    }

    @Transactional(readOnly = true)
    public Page<Statement> listForCustomer(String customerId, Pageable pageable) {
        return statementRepo.findByCustomerId(customerId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Statement> listForAdmin(Pageable pageable) {
        return statementRepo.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Statement getForCustomer(UUID id, String customerId) {
        return statementRepo.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new NotFoundException("Statement not found"));
    }

    @Transactional(readOnly = true)
    public Statement getForAdmin(UUID id) {
        return statementRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Statement not found"));
    }

    @Transactional(readOnly = true)
    public String presignDownloadUrl(Statement s, Duration ttl) {
        validateDownloadable(s);

        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(s3Props.bucket())
                .key(s.getObjectKey())
                .responseContentType("application/pdf")
                .build();

        GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get)
                .build();

        return presigner.presignGetObject(req).url().toString();
    }

    public void validateDownloadable(Statement s) {
        if (s.getStatus() != StatementStatus.ACTIVE) {
            throw new BadRequestException("Statement is not available for download");
        }
    }

    @Transactional
    public Statement revoke(UUID statementId) {
        Statement s = statementRepo.findById(statementId)
                .orElseThrow(() -> new NotFoundException("Statement not found"));
        s.revoke();
        Statement saved = statementRepo.save(s);
        metrics.revokeSuccess();
        return saved;
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
