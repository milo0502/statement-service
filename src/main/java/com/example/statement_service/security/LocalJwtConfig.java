package com.example.statement_service.security;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Local-only HMAC JWT decoder used by the development token endpoint.
 */
@Configuration
@Profile({"local", "dev"})
@EnableConfigurationProperties({JwtValidationProperties.class, LocalJwtProperties.class})
public class LocalJwtConfig {

    @Bean
    JwtDecoder jwtDecoder(JwtValidationProperties jwtProps, LocalJwtProperties localProps) {
        byte[] keyBytes = Base64.getDecoder().decode(localProps.secretBase64());
        if (keyBytes.length < 32) {
            throw new IllegalStateException("Local JWT HMAC secret must be at least 256 bits after Base64 decoding");
        }

        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).build();
        decoder.setJwtValidator(JwtConfig.jwtValidator(jwtProps));
        return decoder;
    }
}
