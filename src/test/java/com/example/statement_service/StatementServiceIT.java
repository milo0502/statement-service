package com.example.statement_service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("docker")
class StatementServiceIT {

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

        // same dev secret as docker-compose; any base64 is fine for tests
        r.add("app.security.jwtSecretBase64", () -> "c3VwZXItc2VjcmV0LXRlc3Qta2V5LXN1cGVyLXNlY3JldC10ZXN0LWtleQ==");
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
            // OK if it already exists (e.g., re-running tests locally)
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

    @BeforeEach
    void setupWebClient() {
        if (webTestClient == null) {
            webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        }
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Test
    void upload_list_presign_and_revoke_flow() throws Exception {
        // 1) Get admin token
        String adminToken = devToken("admin", "admin");

        // 2) Upload PDF as admin
        byte[] pdfBytes = minimalPdfBytes();

        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("customerId", "cust-001");
        mb.part("accountId", "acc-123");
        mb.part("periodStart", LocalDate.of(2025, 12, 1).toString());
        mb.part("periodEnd", LocalDate.of(2025, 12, 31).toString());
        mb.part("file", pdfResource("sample.pdf", pdfBytes))
                .filename("sample.pdf")
                .contentType(MediaType.APPLICATION_PDF);

        String uploadBody = webTestClient.post()
                .uri("/api/v1/statements")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(mb.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(uploadBody).contains("\"id\"");
        String statementId = Json.extract(uploadBody, "id");

        // 3) Customer token
        String custToken = devToken("cust-001", "customer");

        // 4) List statements as customer
        String listBody = webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/statements")
                        .queryParam("page", 0)
                        .queryParam("size", 10)
                        .build())
                .headers(h -> h.setBearerAuth(custToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(listBody).contains(statementId);

        // 5) Presign link
        String presignBody = webTestClient.post()
                .uri("/api/v1/statements/{id}/download-link", statementId)
                .headers(h -> h.setBearerAuth(custToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ttlSeconds\":300}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        String url = Json.extract(presignBody, "url");
        assertThat(url).startsWith("http");

        // 6) Download via presigned URL
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<byte[]> dl = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        assertThat(dl.statusCode()).isEqualTo(200);
        assertThat(dl.body().length).isGreaterThan(10);

        // 6.5) Direct download redirect
        webTestClient.get()
                .uri("/api/v1/statements/{id}/download", statementId)
                .headers(h -> h.setBearerAuth(custToken))
                .exchange()
                .expectStatus().isFound() // 302
                .expectHeader().exists("Location");

        // 6.6) Check audit for DOWNLOAD
        String auditBody = webTestClient.get()
                .uri("/api/v1/audit-events")
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(auditBody).contains("\"action\":\"DOWNLOAD\"");
        assertThat(auditBody).contains("\"statementId\":\"" + statementId + "\"");

        // 7) Revoke (admin)
        webTestClient.post()
                .uri("/api/v1/statements/{id}/revoke", statementId)
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isNoContent();

        // 8) Presign should now fail (400)
        webTestClient.post()
                .uri("/api/v1/statements/{id}/download-link", statementId)
                .headers(h -> h.setBearerAuth(custToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ttlSeconds\":300}")
                .exchange()
                .expectStatus().isBadRequest();

        // 9) Direct download should also fail (400)
        webTestClient.get()
                .uri("/api/v1/statements/{id}/download", statementId)
                .headers(h -> h.setBearerAuth(custToken))
                .exchange()
                .expectStatus().isBadRequest();
    }

    private String devToken(String customerId, String scope) {
        String body = webTestClient.post()
                .uri("/api/v1/dev/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"customerId\":\"" + customerId + "\",\"scope\":\"" + scope + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        return Json.extract(body, "token");
    }

    private ByteArrayResource pdfResource(String filename, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        };
    }

    private byte[] minimalPdfBytes() {
        // tiny but valid-ish PDF header for test purposes
        return ("%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
    }
}
