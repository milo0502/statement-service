package com.example.statement_service.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Request DTO for generating a presigned download link.
 *
 * @param ttlSeconds the time-to-live for the link in seconds, between 30 and 900
 */
public record DownloadLinkRequest(
        @Min(30) @Max(900) int ttlSeconds
) {}