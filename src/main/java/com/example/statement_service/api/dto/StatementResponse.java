package com.example.statement_service.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.example.statement_service.domain.Statement;
import com.example.statement_service.domain.StatementStatus;

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