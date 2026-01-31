package com.example.statement_service.api.dev;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Profile("docker")
@RestController
@RequestMapping("/api/v1/dev")
@Validated
public class DevTokenController {

    private final byte[] secret;

    public DevTokenController(@Value("${app.security.jwtSecretBase64}") String secretB64) {
        this.secret = Base64.getDecoder().decode(secretB64);
    }

    public record DevTokenRequest(
            @NotBlank String customerId,
            String scope // e.g. "customer" or "admin" or "customer admin"
    ) {}

    public record DevTokenResponse(String token) {}

    @PostMapping("/token")
    public DevTokenResponse token(@RequestBody @Validated DevTokenRequest req) throws Exception {
        String scope = (req.scope() == null || req.scope().isBlank()) ? "customer" : req.scope();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(req.customerId())
                .claim("customer_id", req.customerId())
                .claim("scope", scope) // space-separated -> SCOPE_ authorities
                .issuer("statement-service-dev")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(60 * 60))) // 1 hour
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        JWSSigner signer = new MACSigner(secret);
        jwt.sign(signer);

        return new DevTokenResponse(jwt.serialize());
    }
}