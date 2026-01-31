# Secure Statement Delivery Service

Spring Boot service that stores customer PDF account statements and provides secure, time-limited download links (pre-signed URLs).

## Tech
- Java 25, Spring Boot 4.0.2
- Postgres (metadata + audit log) + Flyway
- S3-compatible object storage (MinIO for local)
- OpenAPI (Swagger UI)
- Docker + docker-compose
- Integration tests with Testcontainers (Postgres + MinIO)

## Run locally (Docker)
```bash
docker compose up --build