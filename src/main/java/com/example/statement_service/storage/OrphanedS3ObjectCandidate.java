package com.example.statement_service.storage;

import java.time.LocalDate;
import java.util.UUID;

public record OrphanedS3ObjectCandidate(
        String bucket,
        String objectKey,
        UUID statementId,
        String customerId,
        String accountId,
        LocalDate periodStart,
        LocalDate periodEnd,
        Long sizeBytes,
        String sha256
) {
}
