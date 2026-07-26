package com.example.statement_service.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.orphan-cleanup")
public record OrphanedObjectCleanupProperties(
        boolean enabled,
        int batchSize,
        long minObjectAgeSeconds
) {

    public OrphanedObjectCleanupProperties {
        if (batchSize <= 0) {
            batchSize = 100;
        }
        if (minObjectAgeSeconds < 0) {
            minObjectAgeSeconds = 900;
        }
    }

    public Duration minObjectAge() {
        return Duration.ofSeconds(minObjectAgeSeconds);
    }
}
