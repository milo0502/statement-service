package com.example.statement_service.persistence;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.statement_service.domain.Statement;

/**
 * Repository interface for {@link Statement} entities.
 */
public interface StatementRepository extends JpaRepository<Statement, UUID> {
    /**
     * Finds statements belonging to a specific customer with pagination.
     *
     * @param customerId the ID of the customer
     * @param pageable   pagination information
     * @return a page of statements
     */
    Page<Statement> findByCustomerId(String customerId, Pageable pageable);

    /**
     * Finds a statement by its ID and customer ID.
     * Ensures that a customer can only access their own statements.
     *
     * @param id         the UUID of the statement
     * @param customerId the ID of the customer
     * @return an {@link Optional} containing the statement if found
     */
    Optional<Statement> findByIdAndCustomerId(UUID id, String customerId);

    Optional<Statement> findByCustomerIdAndAccountIdAndPeriodStartAndPeriodEndAndSha256(
            String customerId,
            String accountId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String sha256
    );
}