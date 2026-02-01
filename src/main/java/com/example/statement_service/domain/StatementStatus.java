package com.example.statement_service.domain;

/**
 * Represents the status of a bank statement.
 */
public enum StatementStatus {
    /**
     * The statement is active and can be downloaded.
     */
    ACTIVE,

    /**
     * The statement has been revoked and is no longer available for download.
     */
    REVOKED
}
