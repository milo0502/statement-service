package com.example.statement_service.service;

/**
 * Exception thrown when a request is invalid.
 */
public class BadRequestException extends RuntimeException {
    /**
     * Constructs a new BadRequestException with the specified message.
     *
     * @param message the detail message
     */
    public BadRequestException(String message) {
        super(message);
    }
}
