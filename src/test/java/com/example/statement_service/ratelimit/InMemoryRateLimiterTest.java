package com.example.statement_service.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRateLimiterTest {

    private InMemoryRateLimiter rateLimiter;
    private RateLimitProperties props;

    @BeforeEach
    void setUp() {
        // Limit: 2 requests per 1 second
        props = new RateLimitProperties(2, 1);
        rateLimiter = new InMemoryRateLimiter(props);
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
    void shouldResetLimitAfterWindow() throws InterruptedException {
        assertTrue(rateLimiter.tryConsume("user1"));
        assertTrue(rateLimiter.tryConsume("user1"));
        assertFalse(rateLimiter.tryConsume("user1"));

        // Wait for window to expire (1 second)
        Thread.sleep(1100);

        assertTrue(rateLimiter.tryConsume("user1"));
    }
}
