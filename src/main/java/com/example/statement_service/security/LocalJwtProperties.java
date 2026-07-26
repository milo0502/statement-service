package com.example.statement_service.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security.local-jwt")
public record LocalJwtProperties(
        @NotBlank String secretBase64
) {
}
