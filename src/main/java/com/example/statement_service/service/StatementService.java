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

@Service
public class StatementService {

    private final StatementRepository statementRepo;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Properties s3Props;

    public StatementService(StatementRepository statementRepo, S3Client s3, S3Presigner presigner, S3Properties s3Props) {
        this.statementRepo = statementRepo;
        this.s3 = s3;
        this.presigner = presigner;
        this.s3Props = s3Props;
    }

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
        if (!ct.equalsIgnoreCase("application/pdf")) {
            throw new BadRequestException("Only application/pdf is supported");
        }

        UUID id = UUID.randomUUID();
        String objectKey = "customer/%s/account/%s/%s/%s.pdf"
                .formatted(customerId, accountId, periodStart.getYear() + "-" + String.format("%02d", periodStart.getMonthValue()), id);

        // Use a temp file so we can compute SHA-256 and upload reliably
        Path tmp = null;
        try {
            tmp = Files.createTempFile("statement-", ".pdf");
            try (InputStream in = pdf.getInputStream()) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            String sha256 = sha256Hex(tmp);
            long size = Files.size(tmp);

            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(s3Props.bucket())
                    .key(objectKey)
                    .contentType("application/pdf")
                    .build();

            s3.putObject(put, RequestBody.fromFile(tmp));

            Statement statement = new Statement(
                    id,
                    customerId,
                    accountId,
                    periodStart,
                    periodEnd,
                    objectKey,
                    "application/pdf",
                    size,
                    sha256,
                    Instant.now(),
                    StatementStatus.ACTIVE
            );

            return statementRepo.save(statement);

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

    @Transactional(readOnly = true)
    public Page<Statement> listForCustomer(String customerId, Pageable pageable) {
        return statementRepo.findByCustomerId(customerId, pageable);
    }

    @Transactional(readOnly = true)
    public Statement getForCustomer(UUID id, String customerId) {
        return statementRepo.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new NotFoundException("Statement not found"));
    }

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

    @Transactional
    public Statement revoke(UUID statementId) {
        Statement s = statementRepo.findById(statementId)
                .orElseThrow(() -> new NotFoundException("Statement not found"));
        s.revoke();
        return statementRepo.save(s);
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
