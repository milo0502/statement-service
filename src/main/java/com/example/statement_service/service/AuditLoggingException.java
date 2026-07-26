package com.example.statement_service.service;

public class AuditLoggingException extends RuntimeException {

    public AuditLoggingException(String message, Throwable cause) {
        super(message, cause);
    }
}
