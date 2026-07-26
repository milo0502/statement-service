package com.example.statement_service.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.audit")
public record AuditProperties(
        Boolean required,
        int maxAttempts,
        long retryDelayMillis
) {

    public AuditProperties {
        required = required == null || required;
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
        if (retryDelayMillis < 0) {
            retryDelayMillis = 100;
        }
    }
}
