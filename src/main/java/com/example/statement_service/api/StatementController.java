package com.example.statement_service.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.example.statement_service.ratelimit.InMemoryRateLimiter;
import com.example.statement_service.service.BadRequestException;
import com.example.statement_service.service.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.statement_service.api.dto.DownloadLinkRequest;
import com.example.statement_service.api.dto.DownloadLinkResponse;
import com.example.statement_service.api.dto.StatementResponse;
import com.example.statement_service.domain.Statement;
import com.example.statement_service.security.CurrentCustomer;
import com.example.statement_service.service.AuditService;
import com.example.statement_service.service.StatementService;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * REST controller for managing bank statements.
 * Provides endpoints for uploading, listing, retrieving, and revoking statements,
 * as well as generating download links.
 */
@RestController
@RequestMapping("/api/v1/statements")
public class StatementController {

    private static final Logger log = LoggerFactory.getLogger(StatementController.class);

    private final StatementService statementService;
    private final AuditService auditService;
    private final CurrentCustomer currentCustomer;
    private final InMemoryRateLimiter rateLimiter;

    /**
     * Constructs a new StatementController with the required services.
     *
     * @param statementService the service for statement operations
     * @param auditService     the service for logging audit events
     * @param currentCustomer the helper for getting the current customer from authentication
     */
    public StatementController(StatementService statementService, AuditService auditService, CurrentCustomer currentCustomer, InMemoryRateLimiter rateLimiter) {
        this.statementService = statementService;
        this.auditService = auditService;
        this.currentCustomer = currentCustomer;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Uploads a new statement. Restricted to users with 'admin' scope.
     *
     * @param customerId  the ID of the customer the statement belongs to
     * @param accountId   the ID of the account the statement belongs to
     * @param periodStart the start date of the statement period
     * @param periodEnd   the end date of the statement period
     * @param file        the statement file to upload
     * @param req         the HTTP request for auditing purposes
     * @return the metadata of the uploaded statement
     */
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<StatementResponse> upload(
            @RequestParam("customerId") String customerId,
            @RequestParam("accountId") String accountId,
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam("periodEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest req,
            UriComponentsBuilder uriBuilder
    ) {
        Statement s = statementService.upload(customerId, accountId, periodStart, periodEnd, file);
        auditService.log(customerId, "UPLOAD", s.getId(), req.getRemoteAddr(), req.getHeader("User-Agent"));

        var location = uriBuilder
                .path("/api/v1/statements/{id}")
                .buildAndExpand(s.getId())
                .toUri();

        return ResponseEntity.created(location).body(StatementResponse.from(s));
    }

    /**
     * Lists statements for the authenticated customer. Restricted to 'customer' or 'admin' scope.
     *
     * @param auth     the authentication object
     * @param pageable pagination information
     * @return a page of statement metadata
     */
    @PreAuthorize("hasAuthority('SCOPE_customer') or hasAuthority('SCOPE_admin')")
    @GetMapping
    public Page<StatementResponse> list(Authentication auth, Pageable pageable) {
        String customerId = currentCustomer.customerId(auth);
        return statementService.listForCustomer(customerId, pageable).map(StatementResponse::from);
    }

    /**
     * Retrieves metadata for a specific statement. Restricted to the owner or 'admin'.
     *
     * @param auth the authentication object
     * @param id   the UUID of the statement
     * @return the statement metadata
     */
    @PreAuthorize("hasAuthority('SCOPE_customer') or hasAuthority('SCOPE_admin')")
    @GetMapping("/{id}")
    public StatementResponse get(Authentication auth, @PathVariable UUID id) {
        String customerId = currentCustomer.customerId(auth);
        return StatementResponse.from(statementService.getForCustomer(id, customerId));
    }

    /**
     * Generates a presigned download link for a statement. Restricted to the owner or 'admin'.
     *
     * @param auth    the authentication object
     * @param id      the UUID of the statement
     * @param request the request containing TTL for the link
     * @param http    the HTTP request for auditing purposes
     * @return the download link and its expiration time
     */
    @PreAuthorize("hasAuthority('SCOPE_customer') or hasAuthority('SCOPE_admin')")
    @PostMapping("/{id}/download-link")
    public DownloadLinkResponse downloadLink(
            Authentication auth,
            @PathVariable UUID id,
            @Valid @RequestBody DownloadLinkRequest request,
            HttpServletRequest http
    ) {
        if (!rateLimiter.tryConsume("download-link:" + id)) {
            throw new TooManyRequestsException("Too many download-link requests, please retry later.");
        }

        log.info("Generating download link statementId={} ttlSeconds={}", id, request.ttlSeconds());
        String customerId = currentCustomer.customerId(auth);
        Statement s = statementService.getForCustomer(id, customerId);

        Duration ttl = Duration.ofSeconds(request.ttlSeconds());
        String url = statementService.presignDownloadUrl(s, ttl);

        auditService.log(customerId, "GENERATE_LINK", s.getId(), http.getRemoteAddr(), http.getHeader("User-Agent"));

        return new DownloadLinkResponse(url, Instant.now().plus(ttl));
    }

    /**
     * Revokes a statement, making it unavailable for download. Restricted to 'admin' scope.
     *
     * @param id  the UUID of the statement to revoke
     * @param req the HTTP request for auditing purposes
     */
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    @PostMapping("/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @PathVariable UUID id,
            HttpServletRequest req
    ) {
        var s = statementService.revoke(id);
        auditService.log(s.getCustomerId(), "REVOKE", s.getId(), req.getRemoteAddr(), req.getHeader("User-Agent"));
    }
}