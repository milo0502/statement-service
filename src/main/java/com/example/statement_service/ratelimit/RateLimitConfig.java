package com.example.statement_service.ratelimit;


import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for rate-limiting functionality in the application.
 * <p>
 * This class integrates the rate-limiting properties defined in {@link RateLimitProperties}
 * with the Spring application context. It enables the configuration properties prefixed
 * with "app.ratelimit.download-link" to be loaded and used in other components, such as
 * the {@link InMemoryRateLimiter}.
 * <p>
 * Key Features:
 * - Automatically binds the externalized configuration properties related to rate-limiting
 *   into the {@link RateLimitProperties} class.
 * - Serves as the central configuration component for rate-limiting within the application.
 * <p>
 * Dependencies:
 * - Requires {@link RateLimitProperties} to provide the configuration details like
 *   the maximum request limit and the time window size for rate-limiting.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {}
