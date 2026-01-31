package com.example.statement_service.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.statement_service.domain.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {}
