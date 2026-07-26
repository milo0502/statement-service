package com.example.statement_service.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseRateLimiterTest {

    private MutableClock clock;
    private DatabaseRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:rate-limit-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE rate_limit_windows (
                    rate_limit_key VARCHAR(256) PRIMARY KEY,
                    window_started_at TIMESTAMP NOT NULL,
                    request_count INTEGER NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        rateLimiter = new DatabaseRateLimiter(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new RateLimitProperties(2, 60, RateLimitProperties.FailureMode.DENY),
                clock
        );
    }

    @Test
    void shouldAllowRequestsWithinLimit() {
        assertTrue(rateLimiter.tryConsume("user1"));
        assertTrue(rateLimiter.tryConsume("user1"));
    }

    @Test
    void shouldRejectRequestsExceedingLimit() {
        assertTrue(rateLimiter.tryConsume("user1"));
        assertTrue(rateLimiter.tryConsume("user1"));
        assertFalse(rateLimiter.tryConsume("user1"));
    }

    @Test
    void shouldIsolateDifferentKeys() {
        assertTrue(rateLimiter.tryConsume("user1"));
        assertTrue(rateLimiter.tryConsume("user1"));
        assertFalse(rateLimiter.tryConsume("user1"));

        assertTrue(rateLimiter.tryConsume("user2"));
        assertTrue(rateLimiter.tryConsume("user2"));
        assertFalse(rateLimiter.tryConsume("user2"));
    }

    @Test
    void shouldResetLimitAfterWindow() {
        assertTrue(rateLimiter.tryConsume("user1"));
        assertTrue(rateLimiter.tryConsume("user1"));
        assertFalse(rateLimiter.tryConsume("user1"));

        clock.advance(Duration.ofSeconds(61));

        assertTrue(rateLimiter.tryConsume("user1"));
    }

    @Test
    void shouldDenyWhenStorageFailsAndFailureModeIsDeny() {
        DatabaseRateLimiter brokenLimiter = limiterWithoutSchema(RateLimitProperties.FailureMode.DENY);

        assertFalse(brokenLimiter.tryConsume("user1"));
    }

    @Test
    void shouldAllowWhenStorageFailsAndFailureModeIsAllow() {
        DatabaseRateLimiter brokenLimiter = limiterWithoutSchema(RateLimitProperties.FailureMode.ALLOW);

        assertTrue(brokenLimiter.tryConsume("user1"));
    }

    private DatabaseRateLimiter limiterWithoutSchema(RateLimitProperties.FailureMode failureMode) {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:rate-limit-broken-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        return new DatabaseRateLimiter(
                new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new RateLimitProperties(2, 60, failureMode),
                clock
        );
    }

    private static class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
