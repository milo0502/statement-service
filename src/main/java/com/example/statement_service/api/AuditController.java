package com.example.statement_service.api;

import com.example.statement_service.api.dto.AuditEventResponse;
import com.example.statement_service.persistence.AuditEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for managing audit events.
 * <p>
 * This controller provides endpoints for querying audit event records. Audit events
 * capture user actions within the system for purposes like monitoring and security.
 * <p>
 * The controller uses the {@link AuditEventRepository} to interact with the database
 * and retrieve audit events based on various filters such as customer ID and action type.
 * <p>
 * Security:
 * - Access to the endpoints is restricted to users with the "SCOPE_admin" authority.
 * <p>
 * Endpoints:
 * - GET /api/v1/audit-events: Retrieves a paginated list of audit events. Optional
 *   query parameters `customerId` and `action` can be used to filter the results.
 * <p>
 * Dependencies:
 * - {@link AuditEventRepository}: Repository for querying audit events from the database.
 */
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {

    private final AuditEventRepository repo;

    public AuditController(AuditEventRepository repo) {
        this.repo = repo;
    }

    /**
     * Retrieves a paginated list of audit events.
     * <p>
     * The method supports optional filtering by `customerId` and/or `action`. If both are
     * provided, the results are filtered by both fields. If only one filter is provided,
     * it retrieves audit events matching that filter. If no filters are provided, all audit
     * events are retrieved.
     * <p>
     * Access to this endpoint is restricted to users with the "SCOPE_admin" authority.
     *
     * @param customerId an optional filter specifying the customer ID whose audit events
     *                   should be retrieved
     * @param action     an optional filter specifying the action type of the audit events
     *                   being retrieved
     * @param pageable   the pagination details including page number, size, and sorting
     * @return a paginated {@link Page} of {@link AuditEventResponse} objects representing
     *         the filtered or unfiltered list of audit events
     */
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    @GetMapping
    public Page<AuditEventResponse> list(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String action,
            Pageable pageable
    ) {
        var page =
                (customerId != null && action != null) ? repo.findByCustomerIdAndAction(customerId, action, pageable)
                        : (customerId != null) ? repo.findByCustomerId(customerId, pageable)
                        : (action != null) ? repo.findByAction(action, pageable)
                        : repo.findAll(pageable);

        return page.map(AuditEventResponse::from);
    }
}
