package com.example.statement_service.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Helper component for extracting customer information from the security context.
 */
@Component
public class CurrentCustomer {

    /**
     * Extracts the customer ID from the authenticated JWT token.
     *
     * @param auth the authentication object
     * @return the customer ID
     * @throws AccessDeniedException if the authentication is invalid or the claim is missing
     */
    public String customerId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new AccessDeniedException("No JWT principal found");
        }
        Object v = jwt.getClaims().get("customer_id");
        if (v == null) throw new AccessDeniedException("Missing claim customer_id");
        return v.toString();
    }

    public boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(authority -> "SCOPE_admin".equals(authority.getAuthority()));
    }
}
