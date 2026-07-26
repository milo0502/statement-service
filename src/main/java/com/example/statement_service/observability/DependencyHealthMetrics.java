package com.example.statement_service.observability;

import javax.sql.DataSource;

import com.example.statement_service.storage.S3HealthIndicator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

@Component
public class DependencyHealthMetrics {

    private final DataSource dataSource;
    private final S3HealthIndicator s3HealthIndicator;

    public DependencyHealthMetrics(
            MeterRegistry registry,
            DataSource dataSource,
            S3HealthIndicator s3HealthIndicator
    ) {
        this.dataSource = dataSource;
        this.s3HealthIndicator = s3HealthIndicator;

        Gauge.builder("statement.dependency.health", this, DependencyHealthMetrics::databaseUp)
                .tag("dependency", "db")
                .description("Dependency health status. 1 means up, 0 means down.")
                .register(registry);
        Gauge.builder("statement.dependency.health", this, DependencyHealthMetrics::s3Up)
                .tag("dependency", "s3")
                .description("Dependency health status. 1 means up, 0 means down.")
                .register(registry);
    }

    private double databaseUp() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(1) ? 1.0 : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double s3Up() {
        return Status.UP.equals(s3HealthIndicator.health().getStatus()) ? 1.0 : 0.0;
    }
}
