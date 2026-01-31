package com.example.statement_service.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/v1/statements")
public class StatementController {

    private final StatementService statementService;
    private final AuditService auditService;
    private final CurrentCustomer currentCustomer;

    public StatementController(StatementService statementService, AuditService auditService, CurrentCustomer currentCustomer) {
        this.statementService = statementService;
        this.auditService = auditService;
        this.currentCustomer = currentCustomer;
    }

    // ADMIN upload
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    @PostMapping(consumes = "multipart/form-data")
    public StatementResponse upload(
            @RequestParam("customerId") String customerId,
            @RequestParam("accountId") String accountId,
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam("periodEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest req
    ) {
        Statement s = statementService.upload(customerId, accountId, periodStart, periodEnd, file);
        auditService.log(customerId, "UPLOAD", s.getId(), req.getRemoteAddr(), req.getHeader("User-Agent"));
        return StatementResponse.from(s);
    }

    // CUSTOMER list own
    @PreAuthorize("hasAuthority('SCOPE_customer') or hasAuthority('SCOPE_admin')")
    @GetMapping
    public Page<StatementResponse> list(Authentication auth, Pageable pageable) {
        String customerId = currentCustomer.customerId(auth);
        return statementService.listForCustomer(customerId, pageable).map(StatementResponse::from);
    }

    // CUSTOMER get own metadata
    @PreAuthorize("hasAuthority('SCOPE_customer') or hasAuthority('SCOPE_admin')")
    @GetMapping("/{id}")
    public StatementResponse get(Authentication auth, @PathVariable UUID id) {
        String customerId = currentCustomer.customerId(auth);
        return StatementResponse.from(statementService.getForCustomer(id, customerId));
    }

    // CUSTOMER generate presigned link
    @PreAuthorize("hasAuthority('SCOPE_customer') or hasAuthority('SCOPE_admin')")
    @PostMapping("/{id}/download-link")
    public DownloadLinkResponse downloadLink(
            Authentication auth,
            @PathVariable UUID id,
            @Valid @RequestBody DownloadLinkRequest request,
            HttpServletRequest http
    ) {
        String customerId = currentCustomer.customerId(auth);
        Statement s = statementService.getForCustomer(id, customerId);

        Duration ttl = Duration.ofSeconds(request.ttlSeconds());
        String url = statementService.presignDownloadUrl(s, ttl);

        auditService.log(customerId, "GENERATE_LINK", s.getId(), http.getRemoteAddr(), http.getHeader("User-Agent"));

        return new DownloadLinkResponse(url, Instant.now().plus(ttl));
    }

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