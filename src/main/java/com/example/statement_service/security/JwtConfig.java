package com.example.statement_service.security;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Configuration for JWT decoding.
 */
@Configuration
public class JwtConfig {

    /**
     * Creates a {@link JwtDecoder} using a shared secret key.
     *
     * @param secretB64 the Base64-encoded JWT secret
     * @return the JWT decoder
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.security.jwtSecretBase64}") String secretB64) {
        byte[] keyBytes = Base64.getDecoder().decode(secretB64);
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }
}
