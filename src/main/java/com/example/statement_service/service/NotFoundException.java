package com.example.statement_service.service;

/**
 * Exception thrown when a requested resource is not found.
 */
public class NotFoundException extends RuntimeException {
    /**
     * Constructs a new NotFoundException with the specified message.
     *
     * @param message the detail message
     */
    public NotFoundException(String message) {
        super(message);
    }
}