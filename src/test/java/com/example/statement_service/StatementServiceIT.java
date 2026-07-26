package com.example.statement_service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.example.statement_service.security.JwtConfig;
import com.example.statement_service.security.JwtValidationProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("docker")
class StatementServiceIT {

    private static final String JWT_ISSUER = "http://issuer.test";
    private static final String JWT_AUDIENCE = "statement-service-test";
    private static final KeyPair JWT_KEY_PAIR = createJwtKeyPair();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("statements")
            .withUsername("statements")
            .withPassword("statements");

    @Container
    static MinIOContainer minio = new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
            .withUserName("minio")
            .withPassword("minio12345");

    static final String BUCKET = "statements";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.flyway.enabled", () -> "true");

        r.add("app.s3.endpoint", () -> minio.getS3URL());
        r.add("app.s3.region", () -> "af-south-1");
        r.add("app.s3.accessKey", () -> "minio");
        r.add("app.s3.secretKey", () -> "minio12345");
        r.add("app.s3.bucket", () -> BUCKET);

        r.add("app.ratelimit.download-link.limit", () -> "2");
        r.add("app.ratelimit.download-link.window-seconds", () -> "60");
        r.add("app.security.jwt.issuer", () -> JWT_ISSUER);
        r.add("app.security.jwt.audience", () -> JWT_AUDIENCE);
        r.add("app.security.jwt.jwk-set-uri", () -> "http://issuer.test/.well-known/jwks.json");
    }

    @BeforeAll
    static void initBucket() {
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(minio.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("minio", "minio12345")))
                .region(Region.of("af-south-1"))
                .forcePathStyle(true)
                .build();

        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (S3Exception e) {
            if (e.awsErrorDetails() == null || e.awsErrorDetails().errorCode() == null
                    || !"BucketAlreadyOwnedByYou".equals(e.awsErrorDetails().errorCode())) {
                throw e;
            }
        } finally {
            s3.close();
        }
    }

    @Autowired(required = false)
    private WebTestClient webTestClient;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @BeforeEach
    void setupWebClient() {
        if (webTestClient == null) {
            webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        }
    }

    @Test
    void uploadSuccessReturnsCreatedLocationAndDownloadLinkWorks() throws Exception {
        String customerId = "cust-success-" + UUID.randomUUID();
        String adminToken = devToken("admin", "admin");
        String customerToken = devToken(customerId, "customer");

        String statementId = uploadStatement(adminToken, customerId, "acc-123", LocalDate.of(2025, 12, 1), minimalPdfBytes())
                .expectStatus().isCreated()
                .expectHeader().exists(HttpHeaders.LOCATION)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        statementId = Json.extract(statementId, "id");

        String presignBody = webTestClient.post()
                .uri("/api/v1/statements/{id}/download-link", statementId)
                .headers(h -> h.setBearerAuth(customerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ttlSeconds\":300}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        String url = Json.extract(presignBody, "url");
        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );

        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).hasSizeGreaterThan(10);
    }

    @Test
    void duplicateUploadIsIdempotent() {
        String customerId = "cust-duplicate-" + UUID.randomUUID();
        String adminToken = devToken("admin", "admin");
        LocalDate periodStart = LocalDate.of(2025, 11, 1);
        byte[] pdf = minimalPdfBytes();

        String firstBody = uploadStatement(adminToken, customerId, "acc-dup", periodStart, pdf)
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        String secondBody = uploadStatement(adminToken, customerId, "acc-dup", periodStart, pdf)
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(Json.extract(secondBody, "id")).isEqualTo(Json.extract(firstBody, "id"));
        assertThat(Json.extract(secondBody, "sha256")).isEqualTo(Json.extract(firstBody, "sha256"));
    }

    @Test
    void revokedStatementCannotGenerateDownloadLink() {
        String customerId = "cust-revoked-" + UUID.randomUUID();
        String adminToken = devToken("admin", "admin");
        String customerToken = devToken(customerId, "customer");
        String statementId = uploadedStatementId(adminToken, customerId, "acc-revoked", LocalDate.of(2025, 10, 1));

        webTestClient.post()
                .uri("/api/v1/statements/{id}/revoke", statementId)
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.post()
                .uri("/api/v1/statements/{id}/download-link", statementId)
                .headers(h -> h.setBearerAuth(customerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ttlSeconds\":300}")
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.get()
                .uri("/api/v1/statements/{id}/download", statementId)
                .headers(h -> h.setBearerAuth(customerToken))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void metadataReadsDoNotConsumeDownloadQuotaAndDownloadPathsShareRateLimit() {
        String customerId = "cust-rate-" + UUID.randomUUID();
        String adminToken = devToken("admin", "admin");
        String customerToken = devToken(customerId, "customer");
        String statementId = uploadedStatementId(adminToken, customerId, "acc-rate", LocalDate.of(2025, 9, 1));

        for (int i = 0; i < 3; i++) {
            webTestClient.get()
                    .uri("/api/v1/statements/{id}", statementId)
                    .headers(h -> h.setBearerAuth(customerToken))
                    .exchange()
                    .expectStatus().isOk();
        }

        webTestClient.post()
                .uri("/api/v1/statements/{id}/download-link", statementId)
                .headers(h -> h.setBearerAuth(customerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ttlSeconds\":300}")
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/api/v1/statements/{id}/download", statementId)
                .headers(h -> h.setBearerAuth(customerToken))
                .exchange()
                .expectStatus().isFound()
                .expectHeader().exists(HttpHeaders.LOCATION);

        webTestClient.post()
                .uri("/api/v1/statements/{id}/download-link", statementId)
                .headers(h -> h.setBearerAuth(customerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ttlSeconds\":300}")
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void adminCanListReadGenerateLinkRedirectDownloadAndRevokeAcrossCustomers() {
        String adminToken = devToken("admin", "admin");
        String firstCustomer = "cust-admin-first-" + UUID.randomUUID();
        String secondCustomer = "cust-admin-second-" + UUID.randomUUID();
        String secondCustomerToken = devToken(secondCustomer, "customer");
        String firstStatementId = uploadedStatementId(adminToken, firstCustomer, "acc-admin-1", LocalDate.of(2025, 7, 1));
        String secondStatementId = uploadedStatementId(adminToken, secondCustomer, "acc-admin-2", LocalDate.of(2025, 6, 1));

        String listBody = webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/statements")
                        .queryParam("page", 0)
                        .queryParam("size", 100)
                        .build())
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(listBody).contains(firstStatementId, secondStatementId, firstCustomer, secondCustomer);

        webTestClient.get()
                .uri("/api/v1/statements/{id}", firstStatementId)
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains(firstStatementId, firstCustomer));

        webTestClient.post()
                .uri("/api/v1/statements/{id}/download-link", firstStatementId)
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ttlSeconds\":300}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(Json.extract(body, "url")).startsWith("http"));

        webTestClient.get()
                .uri("/api/v1/statements/{id}/download", firstStatementId)
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isFound()
                .expectHeader().exists(HttpHeaders.LOCATION);

        webTestClient.get()
                .uri("/api/v1/statements/{id}/download", secondStatementId)
                .headers(h -> h.setBearerAuth(secondCustomerToken))
                .exchange()
                .expectStatus().isFound()
                .expectHeader().exists(HttpHeaders.LOCATION);

        webTestClient.post()
                .uri("/api/v1/statements/{id}/revoke", firstStatementId)
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get()
                .uri("/api/v1/statements/{id}", firstStatementId)
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\"status\":\"REVOKED\""));
    }

    @Test
    void auditEndpointIsAdminOnly() {
        String customerToken = devToken("cust-audit", "customer");
        String adminToken = devToken("admin", "admin");

        webTestClient.get()
                .uri("/api/v1/audit-events")
                .headers(h -> h.setBearerAuth(customerToken))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.get()
                .uri("/api/v1/audit-events")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void invalidRequestParametersReturnBadRequest() {
        String adminToken = devToken("admin", "admin");

        webTestClient.get()
                .uri("/api/v1/statements?page=-1")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.get()
                .uri("/api/v1/statements?size=0")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.get()
                .uri("/api/v1/statements?size=101")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.get()
                .uri("/api/v1/statements?sort=sha256")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.get()
                .uri("/api/v1/audit-events?page=-1")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.get()
                .uri("/api/v1/audit-events?size=0")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.get()
                .uri("/api/v1/audit-events?size=101")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.get()
                .uri("/api/v1/audit-events?sort=sha256")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.get()
                .uri("/api/v1/audit-events?action=DELETE")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.get()
                .uri("/api/v1/audit-events?customerId=../customer")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isBadRequest();

        uploadStatement(
                adminToken,
                "cust-invalid-period",
                "acc-invalid-period",
                LocalDate.of(2025, 1, 31),
                LocalDate.of(2025, 1, 1),
                "sample.pdf",
                MediaType.APPLICATION_PDF,
                minimalPdfBytes()
        )
                .expectStatus().isBadRequest();
    }

    @Test
    void invalidUploadsReturnBadRequestAtHttpBoundary() {
        String adminToken = devToken("admin", "admin");

        uploadStatement(
                adminToken,
                "cust-empty-file",
                "acc-empty-file",
                LocalDate.of(2025, 5, 1),
                LocalDate.of(2025, 5, 31),
                "empty.pdf",
                MediaType.APPLICATION_PDF,
                new byte[0]
        )
                .expectStatus().isBadRequest();

        uploadStatement(
                adminToken,
                "cust-wrong-content-type",
                "acc-wrong-content-type",
                LocalDate.of(2025, 5, 1),
                LocalDate.of(2025, 5, 31),
                "statement.txt",
                MediaType.TEXT_PLAIN,
                minimalPdfBytes()
        )
                .expectStatus().isBadRequest();

        uploadStatement(
                adminToken,
                "cust-fake-pdf",
                "acc-fake-pdf",
                LocalDate.of(2025, 5, 1),
                LocalDate.of(2025, 5, 31),
                "statement.pdf",
                MediaType.APPLICATION_PDF,
                "not a pdf".getBytes(StandardCharsets.UTF_8)
        )
                .expectStatus().isBadRequest();

        uploadStatement(
                adminToken,
                "cust-bad-filename",
                "acc-bad-filename",
                LocalDate.of(2025, 5, 1),
                LocalDate.of(2025, 5, 31),
                "../statement.pdf",
                MediaType.APPLICATION_PDF,
                minimalPdfBytes()
        )
                .expectStatus().isBadRequest();

        uploadStatement(
                adminToken,
                "../customer",
                "acc-bad-metadata",
                LocalDate.of(2025, 5, 1),
                LocalDate.of(2025, 5, 31),
                "statement.pdf",
                MediaType.APPLICATION_PDF,
                minimalPdfBytes()
        )
                .expectStatus().isBadRequest();
    }

    @Test
    void unauthenticatedCallersCannotUseAnyDownloadPath() {
        webTestClient.post()
                .uri("/api/v1/statements/{id}/download-link", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ttlSeconds\":300}")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/api/v1/statements/{id}/download", UUID.randomUUID())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void devTokenEndpointIsUnavailableWithoutLocalProfile() {
        webTestClient.post()
                .uri("/api/v1/dev/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"customerId\":\"cust-dev\",\"scope\":\"customer\"}")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.post()
                .uri("/api/v1/dev/token")
                .headers(h -> h.setBearerAuth(devToken("admin", "admin")))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"customerId\":\"cust-dev\",\"scope\":\"customer\"}")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void operationalAndDocumentationEndpointsAreNotPublic() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/actuator/info")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/actuator/metrics")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/swagger-ui/index.html")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void jwtValidationRejectsMissingMalformedExpiredWrongIssuerWrongAudienceAndMissingCustomerClaimTokens() {
        webTestClient.get()
                .uri("/api/v1/statements")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/api/v1/statements")
                .headers(h -> h.setBearerAuth("not-a-jwt"))
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/api/v1/statements")
                .headers(h -> h.setBearerAuth(testToken(
                        "cust-expired",
                        "customer",
                        JWT_ISSUER,
                        JWT_AUDIENCE,
                        Instant.now().minusSeconds(60)
                )))
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/api/v1/statements")
                .headers(h -> h.setBearerAuth(testToken(
                        "cust-wrong-issuer",
                        "customer",
                        "http://wrong-issuer.test",
                        JWT_AUDIENCE,
                        Instant.now().plusSeconds(60 * 60)
                )))
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/api/v1/statements")
                .headers(h -> h.setBearerAuth(testToken(
                        "cust-wrong-audience",
                        "customer",
                        JWT_ISSUER,
                        "wrong-audience",
                        Instant.now().plusSeconds(60 * 60)
                )))
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/api/v1/statements")
                .headers(h -> h.setBearerAuth(testTokenWithoutCustomerId(
                        "customer",
                        JWT_ISSUER,
                        JWT_AUDIENCE,
                        Instant.now().plusSeconds(60 * 60)
                )))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void customerCannotAccessAnotherCustomersStatement() {
        String ownerCustomerId = "cust-owner-" + UUID.randomUUID();
        String otherCustomerId = "cust-other-" + UUID.randomUUID();
        String adminToken = devToken("admin", "admin");
        String otherCustomerToken = devToken(otherCustomerId, "customer");
        String statementId = uploadedStatementId(adminToken, ownerCustomerId, "acc-owner", LocalDate.of(2025, 8, 1));

        webTestClient.get()
                .uri("/api/v1/statements/{id}", statementId)
                .headers(h -> h.setBearerAuth(otherCustomerToken))
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.post()
                .uri("/api/v1/statements/{id}/download-link", statementId)
                .headers(h -> h.setBearerAuth(otherCustomerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ttlSeconds\":300}")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.get()
                .uri("/api/v1/statements/{id}/download", statementId)
                .headers(h -> h.setBearerAuth(otherCustomerToken))
                .exchange()
                .expectStatus().isNotFound();
    }

    private WebTestClient.ResponseSpec uploadStatement(
            String adminToken,
            String customerId,
            String accountId,
            LocalDate periodStart,
            byte[] pdfBytes
    ) {
        return uploadStatement(
                adminToken,
                customerId,
                accountId,
                periodStart,
                periodStart.withDayOfMonth(periodStart.lengthOfMonth()),
                "sample.pdf",
                MediaType.APPLICATION_PDF,
                pdfBytes
        );
    }

    private WebTestClient.ResponseSpec uploadStatement(
            String adminToken,
            String customerId,
            String accountId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String filename,
            MediaType contentType,
            byte[] pdfBytes
    ) {
        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("customerId", customerId);
        mb.part("accountId", accountId);
        mb.part("periodStart", periodStart.toString());
        mb.part("periodEnd", periodEnd.toString());
        mb.part("file", pdfResource(filename, pdfBytes))
                .filename(filename)
                .contentType(contentType);

        return webTestClient.post()
                .uri("/api/v1/statements")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(mb.build()))
                .exchange();
    }

    private String uploadedStatementId(String adminToken, String customerId, String accountId, LocalDate periodStart) {
        String body = uploadStatement(adminToken, customerId, accountId, periodStart, minimalPdfBytes())
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        return Json.extract(body, "id");
    }

    private String devToken(String customerId, String scope) {
        return testToken(customerId, scope, JWT_ISSUER, JWT_AUDIENCE, Instant.now().plusSeconds(60 * 60));
    }

    private String testToken(String customerId, String scope, String issuer, String audience, Instant expiresAt) {
        return testToken(customerId, scope, issuer, audience, expiresAt, true);
    }

    private String testTokenWithoutCustomerId(String scope, String issuer, String audience, Instant expiresAt) {
        return testToken("missing-customer-claim", scope, issuer, audience, expiresAt, false);
    }

    private String testToken(String customerId, String scope, String issuer, String audience, Instant expiresAt, boolean includeCustomerId) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(customerId)
                    .claim("scope", scope)
                    .audience(List.of(audience))
                    .issuer(issuer)
                    .issueTime(new Date())
                    .expirationTime(Date.from(expiresAt));

            if (includeCustomerId) {
                claims.claim("customer_id", customerId);
            }

            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            JWSSigner signer = new RSASSASigner(JWT_KEY_PAIR.getPrivate());
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create test JWT", e);
        }
    }

    private static KeyPair createJwtKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create test JWT key pair", e);
        }
    }

    @TestConfiguration
    static class TestJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withPublicKey((RSAPublicKey) JWT_KEY_PAIR.getPublic())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            decoder.setJwtValidator(JwtConfig.jwtValidator(
                    new JwtValidationProperties(JWT_ISSUER, JWT_AUDIENCE, "http://issuer.test/.well-known/jwks.json")
            ));
            return decoder;
        }
    }

    private ByteArrayResource pdfResource(String filename, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private byte[] minimalPdfBytes() {
        return ("%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
    }
}
