# Secure Statement Delivery Service

A backend service that stores customer account statements as PDF files and provides secure, time-limited download links. Built and structured as a real-world production service.

## Features

- **Secure Storage**: PDF statements stored in S3-compatible object storage.
- **Pre-signed URLs**: Time-limited download links generated for secure access.
- **Security**: JWT-based authentication and authorization with Role-Based Access Control (RBAC).
- **Audit Logging**: Full traceability of uploads, link generations, and downloads.
- **Idempotency**: Safe upload retries using file hashing.
- **Rate Limiting**: Protection for sensitive endpoints.
- **Observability**: Correlation IDs for request tracing across logs.
- **Containerized**: Full Docker environment for local development and testing.
- **Integration Tests**: Comprehensive tests using real infrastructure with Testcontainers.

## Tech Stack

- **Java 25**
- **Spring Boot 4.0.2**
- **PostgreSQL**: Metadata and audit logs.
- **MinIO**: S3-compatible object storage.
- **Flyway**: Database migrations.
- **Spring Security**: JWT resource server.
- **Docker & Docker Compose**
- **Testcontainers**: Infrastructure for integration tests.
- **OpenAPI / Swagger UI**

## Project Structure

```text
statement-service/
├─ src/main/java/
│  ├─ api/            # REST controllers + DTOs
│  ├─ domain/         # JPA entities
│  ├─ persistence/    # Spring Data repositories
│  ├─ service/        # Business logic
│  ├─ security/       # JWT & auth helpers
│  ├─ storage/        # S3 / MinIO integration
│  ├─ ratelimit/      # In-memory rate limiter
│  └─ observability/  # Correlation ID filter
├─ src/main/resources/
│  ├─ db/migration/   # Flyway SQL migrations
│  ├─ application.yml
│  ├─ application-docker.yml
│  └─ logback-spring.xml
├─ src/test/java/     # Integration tests (Testcontainers)
├─ Dockerfile
├─ docker-compose.yml
└─ README.md
```

## Prerequisites

- **Docker + Docker Compose**
- **Java 25** (for local builds)
- **Git** (for build metadata)

## Build & Run

### Local Build
Uses Maven Wrapper and Java 25:
```bash
./mvnw clean package
```
To skip tests:
```bash
./mvnw clean package -DskipTests
```

### Run with Docker (Recommended)
Start the full stack (application, Postgres, MinIO):
```bash
docker compose up --build
```

### Services Started
- **API**: [http://localhost:8080](http://localhost:8080)
- **Swagger UI**: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
- **Health Check**: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
- **Build & Git Info**: [http://localhost:8080/actuator/info](http://localhost:8080/actuator/info)
- **MinIO Console**: [http://localhost:9001](http://localhost:9001) (User: `minio`, Pass: `minio12345`)

## Authentication (Dev Mode)

The service exposes a dev-only token endpoint (enabled only in the `docker` profile).

**Get an Admin Token:**
```bash
curl -s -X POST http://localhost:8080/api/v1/dev/token \
-H "Content-Type: application/json" \
-d '{"customerId":"admin","scope":"admin"}'
```

**Get a Customer Token:**
```bash
curl -s -X POST http://localhost:8080/api/v1/dev/token \
-H "Content-Type: application/json" \
-d '{"customerId":"cust-001","scope":"customer"}'
```

Use the returned token in the `Authorization` header: `Bearer <TOKEN>`

## API Usage Examples

### Upload a Statement (Admin only)
```bash
curl -X POST "http://localhost:8080/api/v1/statements" \
-H "Authorization: Bearer <ADMIN_TOKEN>" \
-F customerId=cust-001 \
-F accountId=acc-123 \
-F periodStart=2025-12-01 \
-F periodEnd=2025-12-31 \
-F file=@sample.pdf;type=application/pdf
```

### List Statements (Customer)
```bash
curl -s "http://localhost:8080/api/v1/statements?page=0&size=10" \
-H "Authorization: Bearer <CUSTOMER_TOKEN>"
```

### Generate Download Link (Customer)
```bash
curl -s -X POST "http://localhost:8080/api/v1/statements/<ID>/download-link" \
-H "Authorization: Bearer <CUSTOMER_TOKEN>" \
-H "Content-Type: application/json" \
-d '{"ttlSeconds":300}'
```

### Direct Download Redirect (Customer)
```bash
curl -v "http://localhost:8080/api/v1/statements/<ID>/download" \
-H "Authorization: Bearer <CUSTOMER_TOKEN>"
```

### Revoke a Statement (Admin only)
```bash
curl -i -X POST "http://localhost:8080/api/v1/statements/<ID>/revoke" \
-H "Authorization: Bearer <ADMIN_TOKEN>"
```

### Retrieve Audit Events (Admin only)
```bash
curl -s "http://localhost:8080/api/v1/audit-events?page=0&size=50" \
-H "Authorization: Bearer <ADMIN_TOKEN>"
```
Filters: `customerId`, `action` (e.g., `UPLOAD`, `DOWNLOAD`, `GENERATE_LINK`, `REVOKE`).

## Configuration Features

- **Rate Limiting**: The `/download-link` endpoint is rate-limited per customer (Default: 10 req / 60s). Configure via `app.ratelimit.download-link.*`.
- **Observability**: Every request is assigned a Correlation ID (`X-Correlation-Id`), returned in headers and included in logs via MDC.

## Testing

Run all tests using:
```bash
./mvnw test
```
The tests use **Testcontainers** to launch real Postgres and MinIO instances, covering the full flow from upload to revocation, including security and idempotency checks.

## Production Notes & Trade-offs

- **Pre-signed URLs**: Used to offload file streaming from the application to object storage.
- **Private Storage**: S3 buckets are private; access is only granted via short-lived signed URLs.
- **Idempotency**: SHA-256 hashing ensures duplicate uploads are handled gracefully.
- **Rate Limiting**: Currently in-memory for simplicity. Use Redis for horizontal scaling.
- **Dev Tools**: The token endpoint is strictly for development and disabled in production profiles.