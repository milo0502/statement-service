package com.example.statement_service.api.dev;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.validation.constraints.NotBlank;
import com.example.statement_service.security.JwtValidationProperties;
import com.example.statement_service.security.LocalJwtProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Date;

/**
 * Controller for generating development tokens.
 * Only active for explicit local development profiles.
 */
@Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v1/dev")
@Validated
public class DevTokenController {

    private final byte[] secret;
    private final JwtValidationProperties jwtProps;

    /**
     * Constructs a new DevTokenController.
     *
     * @param localProps the local JWT signing properties
     * @param jwtProps the JWT validation properties
     */
    public DevTokenController(LocalJwtProperties localProps, JwtValidationProperties jwtProps) {
        this.secret = Base64.getDecoder().decode(localProps.secretBase64());
        this.jwtProps = jwtProps;
    }

    /**
     * Request DTO for generating a development token.
     *
     * @param customerId the ID of the customer
     * @param scope      the scope(s) for the token (e.g., "customer", "admin")
     */
    public record DevTokenRequest(
            @NotBlank String customerId,
            String scope // e.g. "customer" or "admin" or "customer admin"
    ) {}

    /**
     * Response DTO containing the generated token.
     *
     * @param token the serialized JWT
     */
    public record DevTokenResponse(String token) {}

    /**
     * Generates a JWT for development purposes.
     *
     * @param req the token request
     * @return a response containing the generated token
     * @throws Exception if signing the token fails
     */
    @PostMapping("/token")
    public DevTokenResponse token(@RequestBody @Validated DevTokenRequest req) throws Exception {
        String scope = (req.scope() == null || req.scope().isBlank()) ? "customer" : req.scope();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(req.customerId())
                .claim("customer_id", req.customerId())
                .claim("scope", scope) // space-separated -> SCOPE_ authorities
                .audience(List.of(jwtProps.audience()))
                .issuer(jwtProps.issuer())
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(60 * 60))) // 1 hour
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        JWSSigner signer = new MACSigner(secret);
        jwt.sign(signer);

        return new DevTokenResponse(jwt.serialize());
    }
}
