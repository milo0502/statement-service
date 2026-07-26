package com.example.statement_service.ratelimit;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * A configuration properties class that defines the rate-limiting settings for the application.
 * <p>
 * This class is used to bind the externalized configuration properties prefixed with
 * "app.ratelimit.download-link". These properties are typically defined in a configuration file
 * (e.g., application.yml or application.properties) and are used to control the rate-limiting logic
 * in the application.
 * <p>
 * Properties:
 * - {@code limit}: The maximum number of requests allowed within the defined time window.
 * - {@code windowSeconds}: The size of the time window, in seconds, during which the request limit is enforced.
 * <p>
 * This class is designed to work with Spring's {@code @ConfigurationProperties} to provide
 * type-safe access to configuration values.
 */
@Validated
@ConfigurationProperties(prefix = "app.ratelimit.download-link")
public record RateLimitProperties(
        @Min(1)
        int limit,
        @Min(1)
        int windowSeconds,
        FailureMode failureMode
) {

    public RateLimitProperties {
        if (failureMode == null) {
            failureMode = FailureMode.DENY;
        }
    }

    public enum FailureMode {
        ALLOW,
        DENY
    }
}
