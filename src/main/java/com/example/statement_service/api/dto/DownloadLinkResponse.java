package com.example.statement_service.api.dto;

import java.time.Instant;

public record DownloadLinkResponse(
        String url,
        Instant expiresAt
) {}
