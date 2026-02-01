package com.example.statement_service.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for S3.
 *
 * @param endpoint  the S3 endpoint URL
 * @param region    the S3 region
 * @param accessKey the S3 access key
 * @param secretKey the S3 secret key
 * @param bucket    the S3 bucket name
 */
@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(
        String endpoint,
        String externalEndpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket
) {
    public String presignEndpoint() {
        if (externalEndpoint != null && !externalEndpoint.isBlank() && !externalEndpoint.startsWith("${")) {
            return externalEndpoint;
        }
        return endpoint;
    }
}
