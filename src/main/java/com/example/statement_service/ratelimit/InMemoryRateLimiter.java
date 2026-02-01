package com.example.statement_service.ratelimit;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

/**
 * An in-memory rate limiter that restricts the number of operations that can be performed
 * within a defined time window for a given key. This implementation is thread-safe and
 * leverages a concurrent hash map to manage rate-limiting windows for different keys.
 * <p>
 * The rate limiter keeps track of access windows using the {@code Window} record,
 * which stores the start time of the current window and the number of operations
 * performed within that window. The time window size and the maximum allowed
 * operations per window are configured via {@link RateLimitProperties}.
 * <p>
 * Dependencies:
 * - Requires {@link RateLimitProperties} to supply the configuration properties
 *   such as the time window size (in seconds) and the maximum request limit.
 * - Designed to be used as a Spring service component.
 * <p>
 * Use Cases:
 * - Suitable for scenarios where rate limiting based on a distributed datastore is
 *   unnecessary, and an in-memory rate limiter suffices.
 * - Ideal for lightweight systems where maintaining simple rate-limiting logic in memory
 *   is acceptable.
 * <p>
 * Thread Safety:
 * - This class ensures thread safety by using {@code ConcurrentHashMap} for managing window state.
 * - Each rate-limiting window's counter is atomically managed using {@link AtomicInteger}.
 * <p>
 * Limitations:
 * - The in-memory approach makes this implementation unsuitable for distributed systems
 *   where consistent rate limiting across multiple instances of the application is required.
 * <p>
 * Key Methods:
 * - {@link #tryConsume(String)}: Attempts to consume one token within the rate limit for a
 *   specific key. Returns {@code true} if the operation was successful; otherwise, {@code false}.
 */
@Service
public class InMemoryRateLimiter {

    private record Window(Instant start, AtomicInteger count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final RateLimitProperties props;

    /**
     * Constructs an instance of {@code InMemoryRateLimiter}.
     * This rate limiter uses an in-memory approach to enforce rate-limiting rules
     * based on the configuration specified in {@link RateLimitProperties}.
     *
     * @param props the configuration properties that define the time window size (in seconds)
     *              and the maximum number of operations allowed within that window
     *              for rate-limiting purposes
     */
    public InMemoryRateLimiter(RateLimitProperties props) {
        this.props = props;
    }

    /**
     * Attempts to consume a token within the rate limit for the specified key. If the number
     * of operations for the given key within the current time window exceeds the allowed limit,
     * the token consumption will fail.
     * <p>
     * The method ensures thread-safe handling of rate-limiting windows by leveraging a
     * {@link ConcurrentHashMap} to manage state for each key and using {@link AtomicInteger}
     * for atomic updates to counters.
     *
     * @param key the unique identifier for which the rate limit is being enforced. Each key
     *            represents an independent scope for rate limiting.
     * @return {@code true} if the token was successfully consumed without exceeding the rate
     *         limit for the specific key; {@code false} otherwise.
     */
    public boolean tryConsume(String key) {
        final int windowSeconds = props.windowSeconds();
        final int limit = props.limit();

        Instant now = Instant.now();

        Window w = windows.compute(key, (k, existing) -> {
            if (existing == null) {
                return new Window(now, new AtomicInteger(1));
            }
            Instant start = existing.start();
            if (now.isAfter(start.plusSeconds(windowSeconds))) {
                // new window
                return new Window(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });

        return w.count().get() <= limit;
    }
}
