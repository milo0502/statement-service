package com.example.statement_service.storage;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for S3.
 */
@Validated
@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(
        @NotBlank
        String endpoint,
        String externalEndpoint,
        @NotBlank
        String region,
        @NotBlank
        String accessKey,
        @NotBlank
        String secretKey,
        @NotBlank
        String bucket,
        @Min(1)
        int connectionTimeoutMillis,
        @Min(1)
        int socketTimeoutMillis,
        @Min(1)
        int apiCallTimeoutMillis,
        @Min(1)
        int apiCallAttemptTimeoutMillis,
        @Min(0)
        int maxRetries
) {
    public String presignEndpoint() {
        if (externalEndpoint != null && !externalEndpoint.isBlank() && !externalEndpoint.startsWith("${")) {
            return externalEndpoint;
        }
        return endpoint;
    }
}
