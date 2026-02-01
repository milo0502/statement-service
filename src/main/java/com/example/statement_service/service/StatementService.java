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

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.example.statement_service.domain.Statement;
import com.example.statement_service.domain.StatementStatus;
import com.example.statement_service.persistence.StatementRepository;
import com.example.statement_service.storage.S3Properties;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Service for managing bank statements.
 * Handles uploading to S3, metadata persistence in JPA, and link generation.
 */
@Service
public class StatementService {

    private final StatementRepository statementRepo;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Properties s3Props;

    /**
     * Constructs a new StatementService.
     *
     * @param statementRepo the statement repository
     * @param s3            the S3 client
     * @param presigner     the S3 presigner
     * @param s3Props       the S3 configuration properties
     */
    public StatementService(StatementRepository statementRepo, S3Client s3, S3Presigner presigner, S3Properties s3Props) {
        this.statementRepo = statementRepo;
        this.s3 = s3;
        this.presigner = presigner;
        this.s3Props = s3Props;
    }

    /**
     * Uploads a statement file and saves its metadata.
     *
     * @param customerId  the ID of the customer
     * @param accountId   the ID of the account
     * @param periodStart the start date of the period
     * @param periodEnd   the end date of the period
     * @param pdf         the PDF file to upload
     * @return the saved {@link Statement} entity
     * @throws BadRequestException if the file is invalid or required fields are missing
     */
    @Transactional
    public Statement upload(
            String customerId,
            String accountId,
            LocalDate periodStart,
            LocalDate periodEnd,
            MultipartFile pdf
    ) {
        if (pdf == null || pdf.isEmpty()) throw new BadRequestException("PDF file is required");

        if (periodStart == null || periodEnd == null) throw new BadRequestException("periodStart and periodEnd are required");
        if (periodEnd.isBefore(periodStart)) throw new BadRequestException("periodEnd must be on/after periodStart");

        String ct = (pdf.getContentType() == null) ? "" : pdf.getContentType();
        if (!ct.equalsIgnoreCase("application/pdf")) throw new BadRequestException("Only application/pdf is supported");

        Path tmp = null;
        try {
            tmp = Files.createTempFile("statement-", ".pdf");
            try (InputStream in = pdf.getInputStream()) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            String sha256 = sha256Hex(tmp);

            // ✅ idempotency pre-check
            var existing = statementRepo.findByCustomerIdAndAccountIdAndPeriodStartAndPeriodEndAndSha256(
                    customerId, accountId, periodStart, periodEnd, sha256
            );
            if (existing.isPresent()) {
                return existing.get();
            }

            UUID id = UUID.randomUUID();
            String objectKey = "customer/%s/account/%s/%s/%s.pdf"
                    .formatted(customerId, accountId, periodStart.getYear() + "-" + String.format("%02d", periodStart.getMonthValue()), id);

            long size = Files.size(tmp);

            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(s3Props.bucket())
                    .key(objectKey)
                    .contentType("application/pdf")
                    .build();

            s3.putObject(put, RequestBody.fromFile(tmp));

            Statement statement = new Statement(
                    id, customerId, accountId, periodStart, periodEnd,
                    objectKey, "application/pdf", size, sha256,
                    Instant.now(), StatementStatus.ACTIVE
            );

            try {
                return statementRepo.save(statement);
            } catch (DataIntegrityViolationException dup) {
                // ✅ handles race conditions
                return statementRepo.findByCustomerIdAndAccountIdAndPeriodStartAndPeriodEndAndSha256(
                        customerId, accountId, periodStart, periodEnd, sha256
                ).orElseThrow(() -> dup);
            }

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload statement", e);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Lists statements for a customer with pagination.
     *
     * @param customerId the ID of the customer
     * @param pageable   pagination information
     * @return a page of statements
     */
    @Transactional(readOnly = true)
    public Page<Statement> listForCustomer(String customerId, Pageable pageable) {
        return statementRepo.findByCustomerId(customerId, pageable);
    }

    /**
     * Retrieves a statement by ID for a specific customer.
     *
     * @param id         the UUID of the statement
     * @param customerId the ID of the customer
     * @return the statement
     * @throws NotFoundException if the statement is not found or belongs to another customer
     */
    @Transactional(readOnly = true)
    public Statement getForCustomer(UUID id, String customerId) {
        return statementRepo.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new NotFoundException("Statement not found"));
    }

    /**
     * Generates a presigned download URL for a statement.
     *
     * @param s   the statement entity
     * @param ttl the time-to-live for the URL
     * @return the presigned URL
     * @throws BadRequestException if the statement is not ACTIVE
     */
    @Transactional(readOnly = true)
    public String presignDownloadUrl(Statement s, Duration ttl) {
        if (s.getStatus() != StatementStatus.ACTIVE) {
            throw new BadRequestException("Statement is not available for download");
        }

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

    /**
     * Revokes a statement.
     *
     * @param statementId the UUID of the statement to revoke
     * @return the revoked statement
     * @throws NotFoundException if the statement is not found
     */
    @Transactional
    public Statement revoke(UUID statementId) {
        Statement s = statementRepo.findById(statementId)
                .orElseThrow(() -> new NotFoundException("Statement not found"));
        s.revoke();
        return statementRepo.save(s);
    }

    /**
     * Computes SHA-256 hash of a file.
     *
     * @param file the path to the file
     * @return the hex representation of the SHA-256 hash
     * @throws Exception if hashing fails
     */
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
