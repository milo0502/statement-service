package com.example.statement_service.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalJwtConfigTest {

    private static final JwtValidationProperties JWT_PROPERTIES = new JwtValidationProperties(
            "http://localhost:8080/local-issuer",
            "statement-service",
            "http://localhost:8080/.well-known/jwks.json"
    );

    @Test
    void createsDecoderWhenLocalSecretIsAtLeast256Bits() {
        JwtDecoder decoder = new LocalJwtConfig().jwtDecoder(
                JWT_PROPERTIES,
                new LocalJwtProperties("c3VwZXItc2VjcmV0LWRldi1rZXktc3VwZXItc2VjcmV0LWRldi1rZXk=")
        );

        assertThat(decoder).isNotNull();
    }

    @Test
    void rejectsLocalSecretShorterThan256Bits() {
        assertThatThrownBy(() -> new LocalJwtConfig().jwtDecoder(
                JWT_PROPERTIES,
                new LocalJwtProperties("c2hvcnQ=")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 256 bits");
    }
}
