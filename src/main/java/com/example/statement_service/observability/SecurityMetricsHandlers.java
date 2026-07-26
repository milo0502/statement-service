package com.example.statement_service.observability;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class SecurityMetricsHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final StatementMetrics metrics;
    private final AuthenticationEntryPoint unauthorizedDelegate = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler forbiddenDelegate = new BearerTokenAccessDeniedHandler();

    public SecurityMetricsHandlers(StatementMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException, ServletException {
        metrics.authUnauthorized();
        unauthorizedDelegate.commence(request, response, authException);
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        metrics.authForbidden();
        forbiddenDelegate.handle(request, response, accessDeniedException);
    }
}
