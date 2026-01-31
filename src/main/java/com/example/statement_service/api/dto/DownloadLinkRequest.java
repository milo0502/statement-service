package com.example.statement_service.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record DownloadLinkRequest(
        @Min(30) @Max(900) int ttlSeconds
) {}