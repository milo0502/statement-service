package com.example.statement_service.ratelimit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DatabaseRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRateLimiter.class);

    private record Window(Instant startedAt, int requestCount) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final RateLimitProperties props;
    private final Clock clock;

    @Autowired
    public DatabaseRateLimiter(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            RateLimitProperties props
    ) {
        this(jdbc, transactionTemplate, props, Clock.systemUTC());
    }

    DatabaseRateLimiter(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            RateLimitProperties props,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public boolean tryConsume(String key) {
        try {
            return tryConsumeWithRetry(key);
        } catch (RuntimeException e) {
            boolean allowed = props.failureMode() == RateLimitProperties.FailureMode.ALLOW;
            log.warn("Rate limiter storage failure key={} failureMode={} allowed={}", key, props.failureMode(), allowed);
            return allowed;
        }
    }

    private boolean tryConsumeWithRetry(String key) {
        try {
            return consumeInTransaction(key);
        } catch (DuplicateKeyException duplicateFirstRequestRace) {
            return consumeInTransaction(key);
        }
    }

    private boolean consumeInTransaction(String key) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            Window window = jdbc.query(
                    """
                    SELECT window_started_at, request_count
                    FROM rate_limit_windows
                    WHERE rate_limit_key = ?
                    FOR UPDATE
                    """,
                    rs -> rs.next()
                            ? new Window(rs.getTimestamp("window_started_at").toInstant(), rs.getInt("request_count"))
                            : null,
                    key
            );

            if (window == null) {
                insertWindow(key, now);
                return true;
            }

            if (now.isAfter(window.startedAt().plusSeconds(props.windowSeconds()))) {
                updateWindow(key, now, 1);
                return true;
            }

            int newCount = window.requestCount() + 1;
            updateWindow(key, window.startedAt(), newCount);
            return newCount <= props.limit();
        }));
    }

    private void insertWindow(String key, Instant startedAt) {
        jdbc.update(
                """
                INSERT INTO rate_limit_windows (rate_limit_key, window_started_at, request_count)
                VALUES (?, ?, ?)
                """,
                key,
                Timestamp.from(startedAt),
                1
        );
    }

    private void updateWindow(String key, Instant startedAt, int requestCount) {
        jdbc.update(
                """
                UPDATE rate_limit_windows
                SET window_started_at = ?, request_count = ?, updated_at = CURRENT_TIMESTAMP
                WHERE rate_limit_key = ?
                """,
                Timestamp.from(startedAt),
                requestCount,
                key
        );
    }
}
