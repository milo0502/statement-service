package com.example.statement_service.service;

/**
 * Exception thrown when a request is rejected due to exceeding
 * the allowed rate limit.
 * <p>
 * This exception indicates that the client has made too many
 * requests in a short period of time and needs to back off or
 * wait before making further requests. It is typically used in
 * contexts where rate limiting policies are enforced to protect
 * system resources or ensure fair usage.
 */
public class TooManyRequestsException extends RuntimeException {
    /**
     * Constructs a new TooManyRequestsException with the specified detail message.
     * <p>
     * This exception is typically thrown to indicate that a client has exceeded
     * the allowed rate limit for requests and is required to reduce the frequency
     * of requests or wait before making additional requests.
     *
     * @param message the detail message explaining the reason for the exception
     */
    public TooManyRequestsException(String message) {
        super(message);
    }
}
