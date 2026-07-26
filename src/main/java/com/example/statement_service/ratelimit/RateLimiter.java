package com.example.statement_service.ratelimit;

public interface RateLimiter {
    boolean tryConsume(String key);
}
