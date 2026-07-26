package com.example.statement_service.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class StatementMetrics {

    private final MeterRegistry registry;

    public StatementMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void uploadSuccess() {
        increment("statement.upload.success", "upload", "success");
    }

    public void uploadFailure() {
        increment("statement.upload.failure", "upload", "failure");
    }

    public void downloadLinkGenerated() {
        increment("statement.download_link.generated", "download_link", "success");
    }

    public void downloadLinkRateLimited() {
        increment("statement.download_link.rate_limited", "download_link", "rate_limited");
    }

    public void authUnauthorized() {
        increment("statement.auth.failure", "auth", "unauthorized");
    }

    public void authForbidden() {
        increment("statement.auth.failure", "auth", "forbidden");
    }

    public void revokeSuccess() {
        increment("statement.revoke.success", "revoke", "success");
    }

    private void increment(String name, String operation, String outcome) {
        Counter.builder(name)
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
