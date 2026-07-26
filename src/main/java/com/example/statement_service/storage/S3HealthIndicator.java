package com.example.statement_service.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@Component("s3")
public class S3HealthIndicator implements HealthIndicator {

    private final S3Client s3;
    private final S3Properties properties;

    public S3HealthIndicator(S3Client s3, S3Properties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            s3.headBucket(HeadBucketRequest.builder()
                    .bucket(properties.bucket())
                    .build());
            return Health.up().withDetail("bucket", properties.bucket()).build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("bucket", properties.bucket()).build();
        }
    }
}
