package com.example.statement_service.observability;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A filter that ensures each request is associated with a unique correlation ID, which is used
 * for tracking and logging purposes. The correlation ID is retrieved from the incoming request's
 * header or generated if not present. It is then added to the response headers and made available in
 * the MDC (Mapped Diagnostic Context) for the duration of the request.
 * <p>
 * This filter is useful for tracing requests and establishing a relationship between logs and
 * a specific transaction across distributed services.
 * <p>
 * Key responsibilities:
 * - Checks for the presence of a correlation ID in the "X-Correlation-Id" request header.
 * - Generates a new correlation ID if one is not provided or is blank.
 * - Stores the correlation ID in the MDC for logging purposes.
 * - Adds the correlation ID to the response header.
 * - Ensures the MDC is cleared after the request is processed.
 * <p>
 * Extends:
 * - OncePerRequestFilter: Ensures a single execution per request dispatch.
 * <p>
 * Constants:
 * - {@code HEADER}: Specifies the HTTP header name for the correlation ID.
 * - {@code MDC_KEY}: Denotes the key used to store the correlation ID in the MDC.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /**
     * Processes the incoming HTTP request by ensuring a unique correlation ID is associated with it.
     * The method retrieves the correlation ID from the request header or generates a new one if
     * the header is absent or blank. It adds the correlation ID to the response header and the MDC
     * (Mapped Diagnostic Context) for request tracking and logging purposes. Finally, it ensures the
     * MDC is cleared once the request is processed.
     *
     * @param request the HTTP servlet request containing client request data
     * @param response the HTTP servlet response for sending data back to the client
     * @param filterChain the filter chain that allows the request to proceed to the next filter or resource
     * @throws ServletException if an error occurs during the filtering process
     * @throws IOException if an I/O error occurs during the filtering process
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String cid = request.getHeader(HEADER);
        if (cid == null || cid.isBlank()) cid = UUID.randomUUID().toString();

        MDC.put(MDC_KEY, cid);
        response.setHeader(HEADER, cid);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
