package com.example.statement_service.api.dto;

import java.time.Instant;

/**
 * Response DTO containing the presigned download link and its expiration.
 *
 * @param url       the presigned URL
 * @param expiresAt the instant when the link expires
 */
public record DownloadLinkResponse(
        String url,
        Instant expiresAt
) {}
