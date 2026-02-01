package com.example.statement_service.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.example.statement_service.domain.Statement;
import com.example.statement_service.domain.StatementStatus;

/**
 * Response DTO for statement metadata.
 *
 * @param id          the UUID of the statement
 * @param customerId  the ID of the customer
 * @param accountId   the ID of the account
 * @param periodStart the start date of the statement period
 * @param periodEnd   the end date of the statement period
 * @param contentType the MIME type of the statement file
 * @param sizeBytes   the size of the file in bytes
 * @param sha256      the SHA-256 hash of the file
 * @param uploadedAt  the timestamp when the statement was uploaded
 * @param status      the current status of the statement
 */
public record StatementResponse(
        UUID id,
        String customerId,
        String accountId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String contentType,
        long sizeBytes,
        String sha256,
        Instant uploadedAt,
        StatementStatus status
) {
    /**
     * Converts a {@link Statement} domain entity to a {@link StatementResponse} DTO.
     *
     * @param s the statement entity
     * @return the statement response DTO
     */
    public static StatementResponse from(Statement s) {
        return new StatementResponse(
                s.getId(),
                s.getCustomerId(),
                s.getAccountId(),
                s.getPeriodStart(),
                s.getPeriodEnd(),
                s.getContentType(),
                s.getSizeBytes(),
                s.getSha256(),
                s.getUploadedAt(),
                s.getStatus()
        );
    }
}