package com.example.statement_service.api;

import java.time.LocalDate;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.statement_service.service.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

final class ApiRequestValidation {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_METADATA_LENGTH = 128;
    private static final Pattern SAFE_METADATA_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@-]{0,127}");
    private static final Set<String> STATEMENT_SORT_FIELDS = Set.of(
            "uploadedAt", "periodStart", "periodEnd", "customerId", "accountId", "status"
    );
    private static final Set<String> AUDIT_SORT_FIELDS = Set.of(
            "createdAt", "customerId", "action", "statementId"
    );
    private static final Set<String> AUDIT_ACTIONS = Set.of(
            "UPLOAD", "GENERATE_LINK", "DOWNLOAD", "REVOKE"
    );

    private ApiRequestValidation() {
    }

    static void validateCustomerId(String customerId) {
        validateMetadata("customerId", customerId);
    }

    static void validateAccountId(String accountId) {
        validateMetadata("accountId", accountId);
    }

    static void validateOptionalCustomerId(String customerId) {
        if (customerId != null) {
            validateCustomerId(customerId);
        }
    }

    static void validateAuditAction(String action) {
        if (action == null) {
            return;
        }
        if (action.isBlank() || action.length() > 64 || !AUDIT_ACTIONS.contains(action)) {
            throw new BadRequestException("action is not supported");
        }
    }

    static void validatePeriodRange(LocalDate periodStart, LocalDate periodEnd) {
        if (periodStart == null || periodEnd == null) {
            throw new BadRequestException("periodStart and periodEnd are required");
        }
        if (periodEnd.isBefore(periodStart)) {
            throw new BadRequestException("periodEnd must be on/after periodStart");
        }
    }

    static void validateStatementPageable(Pageable pageable) {
        validatePageable(pageable, STATEMENT_SORT_FIELDS);
    }

    static void validateAuditPageable(Pageable pageable) {
        validatePageable(pageable, AUDIT_SORT_FIELDS);
    }

    static void validatePageQuery(HttpServletRequest request) {
        validateIntegerQueryParameter(request, "page", 0, Integer.MAX_VALUE);
        validateIntegerQueryParameter(request, "size", 1, MAX_PAGE_SIZE);
    }

    private static void validateMetadata(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        if (value.length() > MAX_METADATA_LENGTH || !SAFE_METADATA_VALUE.matcher(value).matches()) {
            throw new BadRequestException(field + " contains unsupported characters");
        }
    }

    private static void validatePageable(Pageable pageable, Set<String> allowedSortFields) {
        if (pageable.getPageNumber() < 0) {
            throw new BadRequestException("page must be greater than or equal to 0");
        }
        if (pageable.getPageSize() < 1 || pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!allowedSortFields.contains(order.getProperty())) {
                throw new BadRequestException("sort field is not supported: " + order.getProperty());
            }
        }
    }

    private static void validateIntegerQueryParameter(HttpServletRequest request, String name, int min, int max) {
        String value = request.getParameter(name);
        if (value == null) {
            return;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new BadRequestException(name + " must be between " + min + " and " + max);
            }
        } catch (NumberFormatException e) {
            throw new BadRequestException(name + " must be an integer");
        }
    }
}
