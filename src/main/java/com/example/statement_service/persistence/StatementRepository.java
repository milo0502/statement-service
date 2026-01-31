package com.example.statement_service.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.statement_service.domain.Statement;

public interface StatementRepository extends JpaRepository<Statement, UUID> {
    Page<Statement> findByCustomerId(String customerId, Pageable pageable);
    Optional<Statement> findByIdAndCustomerId(UUID id, String customerId);
}