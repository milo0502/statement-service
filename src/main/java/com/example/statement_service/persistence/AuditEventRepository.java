package com.example.statement_service.persistence;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.statement_service.domain.AuditEvent;

/**
 * Repository interface for {@link AuditEvent} entities.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    Page<AuditEvent> findByAction(String action, Pageable pageable);
    Page<AuditEvent> findByCustomerId(String customerId, Pageable pageable);
    Page<AuditEvent> findByCustomerIdAndAction(String customerId, String action, Pageable pageable);
}
