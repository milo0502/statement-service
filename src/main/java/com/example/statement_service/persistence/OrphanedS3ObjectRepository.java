package com.example.statement_service.persistence;

import java.util.Optional;

import com.example.statement_service.domain.OrphanedS3Object;
import com.example.statement_service.domain.OrphanedS3ObjectStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrphanedS3ObjectRepository extends JpaRepository<OrphanedS3Object, UUID> {

    List<OrphanedS3Object> findByStatusOrderByFirstDetectedAtAsc(
            OrphanedS3ObjectStatus status,
            Pageable pageable
    );

    Optional<OrphanedS3Object> findFirstByBucketAndObjectKeyAndStatusOrderByFirstDetectedAtDesc(
            String bucket,
            String objectKey,
            OrphanedS3ObjectStatus status
    );
}
