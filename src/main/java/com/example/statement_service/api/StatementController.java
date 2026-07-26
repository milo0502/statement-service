package com.example.statement_service.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.example.statement_service.observability.StatementMetrics;
import com.example.statement_service.ratelimit.RateLimiter;
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
    private static final Duration REDIRECT_DOWNLOAD_TTL = Duration.ofMinutes(1);

    private final StatementService statementService;
    private final AuditService auditService;
    private final CurrentCustomer currentCustomer;
    private final RateLimiter rateLimiter;
    private final StatementMetrics metrics;

    /**
     * Constructs a new StatementController with the required services.
     *
     * @param statementService the service for statement operations
     * @param auditService     the service for logging audit events
     * @param currentCustomer the helper for getting the current customer from authentication
     */
    public StatementController(
            StatementService statementService,
            AuditService auditService,
            CurrentCustomer currentCustomer,
            RateLimiter rateLimiter,
            StatementMetrics metrics
    ) {
        this.statementService = statementService;
        this.auditService = auditService;
        this.currentCustomer = currentCustomer;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
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
        ApiRequestValidation.validateCustomerId(customerId);
        ApiRequestValidation.validateAccountId(accountId);
        ApiRequestValidation.validatePeriodRange(periodStart, periodEnd);

        Statement s = statementService.upload(customerId, accountId, periodStart, periodEnd, file);
        auditService.log(customerId, "UPLOAD", s.getId(), req.getRemoteAddr(), req.getHeader("User-Agent"));

        var location = uriBuilder
                .path("/api/v1/statements/{id}")
                .buildAndExpand(s.getId())
                .toUri();

        return ResponseEntity.created(location).body(StatementResponse.from(s));
    }

    /**
     * Lists statements. Customers see their own statements; admins see all statements.
     *
     * @param auth     the authentication object
     * @param pageable pagination information
     * @return a page of statement metadata
     */
    @PreAuthorize("hasAuthority('SCOPE_customer') or hasAuthority('SCOPE_admin')")
    @GetMapping
    public Page<StatementResponse> list(Authentication auth, Pageable pageable, HttpServletRequest req) {
        ApiRequestValidation.validatePageQuery(req);
        ApiRequestValidation.validateStatementPageable(pageable);

        if (currentCustomer.isAdmin(auth)) {
            return statementService.listForAdmin(pageable).map(StatementResponse::from);
        }
        String customerId = currentCustomer.customerId(auth);
        return statementService.listForCustomer(customerId, pageable).map(StatementResponse::from);
    }

    /**
     * Retrieves metadata for a specific statement. Customers can access their own statements; admins can access any statement.
     *
     * @param auth the authentication object
     * @param id   the UUID of the statement
     * @return the statement metadata
     */
    @PreAuthorize("hasAuthority('SCOPE_customer') or hasAuthority('SCOPE_admin')")
    @GetMapping("/{id}")
    public StatementResponse get(Authentication auth, @PathVariable UUID id) {
        if (currentCustomer.isAdmin(auth)) {
            return StatementResponse.from(statementService.getForAdmin(id));
        }
        String customerId = currentCustomer.customerId(auth);
        return StatementResponse.from(statementService.getForCustomer(id, customerId));
    }

    /**
     * Generates a presigned download link for a statement. Customers can access their own statements; admins can access any statement.
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
        log.info("Generating download link statementId={} ttlSeconds={}", id, request.ttlSeconds());
        Statement s = statementForDownload(auth, id);
        statementService.validateDownloadable(s);
        consumeDownloadQuota(id);

        Duration ttl = Duration.ofSeconds(request.ttlSeconds());
        String url = statementService.presignDownloadUrl(s, ttl);

        auditService.log(s.getCustomerId(), "GENERATE_LINK", s.getId(), http.getRemoteAddr(), http.getHeader("User-Agent"));
        metrics.downloadLinkGenerated();

        return new DownloadLinkResponse(url, Instant.now().plus(ttl));
    }

    /**
     * Downloads a statement by redirecting to a presigned URL. Customers can access their own statements; admins can access any statement.
     *
     * @param auth the authentication object
     * @param id   the UUID of the statement
     * @param http the HTTP request for auditing purposes
     * @return a redirect to the presigned S3 URL
     */
    @PreAuthorize("hasAuthority('SCOPE_customer') or hasAuthority('SCOPE_admin')")
    @GetMapping("/{id}/download")
    public ResponseEntity<Void> download(
            Authentication auth,
            @PathVariable UUID id,
            HttpServletRequest http
    ) {
        log.info("Downloading statement id={}", id);
        Statement s = statementForDownload(auth, id);
        statementService.validateDownloadable(s);
        consumeDownloadQuota(id);

        String url = statementService.presignDownloadUrl(s, REDIRECT_DOWNLOAD_TTL);
        auditService.log(s.getCustomerId(), "DOWNLOAD", s.getId(), http.getRemoteAddr(), http.getHeader("User-Agent"));
        metrics.downloadLinkGenerated();

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(java.net.URI.create(url))
                .build();
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

    private Statement statementForDownload(Authentication auth, UUID id) {
        return currentCustomer.isAdmin(auth)
                ? statementService.getForAdmin(id)
                : statementService.getForCustomer(id, currentCustomer.customerId(auth));
    }

    private void consumeDownloadQuota(UUID id) {
        if (!rateLimiter.tryConsume("download-link:" + id)) {
            metrics.downloadLinkRateLimited();
            throw new TooManyRequestsException("Too many download-link requests, please retry later.");
        }
    }
}
