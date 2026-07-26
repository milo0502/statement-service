# Secure Statement Delivery Service

Backend service for storing customer PDF account statements and issuing secure, time-limited download links.

## Architecture

- Spring Boot 4 REST API exposes upload, listing, metadata, revoke, download-link, audit, and actuator endpoints.
- PostgreSQL stores statement metadata, audit events, and the SHA-256 idempotency key.
- Flyway owns production schema changes.
- MinIO provides local S3-compatible object storage; production can use AWS S3 or another compatible store.
- JWT bearer tokens with RBAC protect customer and admin operations.
- Presigned S3 URLs let clients download private PDFs without the application streaming file bytes.

## Key Features

- Admin-only statement upload and revoke.
- Customer-only access to a customer's own statements, returning 404 for another customer's statement.
- SHA-256 idempotency for duplicate uploads using a database unique constraint.
- S3 timeouts and bounded AWS SDK retries for transient network, throttling, and 5xx failures.
- Upload transaction boundary keeps S3 I/O outside the database transaction and cleans up S3 if metadata save fails.
- JSON structured logs with `X-Correlation-Id` propagation.
- Public Actuator health endpoint for platform probes.
- Custom business metrics for upload, download-link, rate-limit, and revoke outcomes.
- Shared database-backed download-link rate limiter.
- Scheduled cleanup for orphaned S3 objects left behind by failed upload metadata writes.
- Production infrastructure controls documented for managed PostgreSQL, private encrypted S3, and least-privilege IAM.

## Prerequisites

- Java 25
- Docker and Docker Compose
- Maven Wrapper, or Maven 3.9+ installed locally

## Build

```bash
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

JaCoCo coverage is generated under `target/site/jacoco/index.html` during `verify`.

## CI Release Gate

GitHub Actions runs `./mvnw clean verify` on pull requests, pushes to `main`, release-candidate tags, version tags, GitHub releases/pre-releases, and manual dispatches. The workflow verifies Docker availability before Maven starts so Testcontainers-backed PostgreSQL and MinIO tests fail fast if the runner cannot start containers.

The same CI job builds the Docker image and scans it with Trivy. Treat the CI job as a required status check in repository branch-protection and release rules so failed migrations, integration tests, security tests, image builds, or vulnerability scans block merge and release.

## Run With Docker

```bash
docker compose up --build
```

Services:

- API: `http://localhost:8080`
- Health: `http://localhost:8080/actuator/health`
- Readiness: `http://localhost:8080/actuator/health/readiness`
- Liveness: `http://localhost:8080/actuator/health/liveness`
- MinIO Console: `http://localhost:9001` using `minio` / `minio12345`

Copy `.env.example` when running outside Compose and replace local secrets before production use.

The runtime image runs as an unprivileged user and copies the deterministic `target/statement-service.jar` artifact. The Dockerfile intentionally does not define `HEALTHCHECK`; production deployments should configure readiness and liveness probes in the orchestrator using the health endpoints above.

Runtime defaults:

- Graceful shutdown is enabled with a 30-second shutdown phase timeout.
- Tomcat connection timeout is 20 seconds, keep-alive timeout is 15 seconds, and max keep-alive requests is 100.
- The container sets `JAVA_TOOL_OPTIONS=-XX:InitialRAMPercentage=25 -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`.
- Multipart uploads use `/tmp/statement-service-uploads` in the container.

Size the upload temp directory as `max_concurrent_uploads * 10MB`, plus at least 50% headroom. For example, 25 concurrent uploads need at least 375MB of writable temp space. The application deletes its copied temp file after upload processing, and servlet multipart parts are cleaned after request completion, but the filesystem should still be monitored.

## Development Tokens

The token endpoint exists only in explicit local development profiles such as `local` or `dev`. It is disabled in the `docker` profile and other production-like profiles.

```bash
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/dev/token \
  -H "Content-Type: application/json" \
  -d '{"customerId":"admin","scope":"admin"}' | jq -r .token)

CUSTOMER_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/dev/token \
  -H "Content-Type: application/json" \
  -d '{"customerId":"cust-001","scope":"customer"}' | jq -r .token)
```

## API Examples

Upload a statement as admin:

```bash
curl -i -X POST "http://localhost:8080/api/v1/statements" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F customerId=cust-001 \
  -F accountId=acc-123 \
  -F periodStart=2025-12-01 \
  -F periodEnd=2025-12-31 \
  -F "file=@sample.pdf;type=application/pdf"
```

List statements as customer:

```bash
curl -s "http://localhost:8080/api/v1/statements?page=0&size=10" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN"
```

Generate a download link:

```bash
curl -s -X POST "http://localhost:8080/api/v1/statements/<STATEMENT_ID>/download-link" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ttlSeconds":300}'
```

The JSON download-link endpoint is the primary API download model. `GET /api/v1/statements/<STATEMENT_ID>/download` is also available as a browser-style redirect convenience path. Both paths require owner-or-admin access, reject revoked statements, and share the same download-link rate limit.

Revoke a statement:

```bash
curl -i -X POST "http://localhost:8080/api/v1/statements/<STATEMENT_ID>/revoke" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Read audit events as admin:

```bash
curl -s "http://localhost:8080/api/v1/audit-events?page=0&size=50" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

## Configuration

Required production variables are shown in `.env.example`:

- `DB_URL`, `DB_USER`, `DB_PASS`
- `S3_ENDPOINT`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET`
- `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_JWK_SET_URI`
- `RATE_LIMIT_DOWNLOAD_LINK_LIMIT`, `RATE_LIMIT_DOWNLOAD_LINK_WINDOW_SECONDS`, `RATE_LIMIT_DOWNLOAD_LINK_FAILURE_MODE`
- `AUDIT_REQUIRED`, `AUDIT_MAX_ATTEMPTS`, `AUDIT_RETRY_DELAY_MILLIS`
- `ORPHAN_CLEANUP_ENABLED`, `ORPHAN_CLEANUP_BATCH_SIZE`, `ORPHAN_CLEANUP_MIN_OBJECT_AGE_SECONDS`, `ORPHAN_CLEANUP_INITIAL_DELAY_MILLIS`, `ORPHAN_CLEANUP_FIXED_DELAY_MILLIS`
- `HIKARI_MAX_POOL_SIZE`, `HIKARI_MIN_IDLE`, `HIKARI_CONNECTION_TIMEOUT_MS`, `HIKARI_IDLE_TIMEOUT_MS`, `HIKARI_MAX_LIFETIME_MS`, `HIKARI_LEAK_DETECTION_THRESHOLD_MS`

`S3_EXTERNAL_ENDPOINT` is useful locally because the application talks to MinIO at `http://minio:9000` inside Docker, while the client must use `http://localhost:9000` in generated presigned URLs.

Production JWT validation uses the configured identity-provider JWKS endpoint, issuer, and audience. The local/dev token endpoint is the only code path that uses an HMAC secret, and its `LOCAL_JWT_SECRET_BASE64` value must decode to at least 256 bits.

## Observability

Logs are emitted as JSON and include timestamp, level, service, logger, thread, correlation ID, and message. The service accepts `X-Correlation-Id` and always returns it in the response header.

Custom counters include:

- `statement.upload.success`
- `statement.upload.failure`
- `statement.download_link.generated`
- `statement.download_link.rate_limited`
- `statement.auth.failure`
- `statement.revoke.success`

Dependency gauges include:

- `statement.dependency.health{dependency="db"}`
- `statement.dependency.health{dependency="s3"}`

Metrics use safe tags such as `operation` and `outcome`. They do not tag customer IDs, statement IDs, JWTs, presigned URLs, or secrets.

Alerting guidance and sample Prometheus rules are in `docs/ALERTING.md` and `ops/prometheus/statement-service-alerts.yml`. Load and abuse testing guidance is in `docs/LOAD_AND_ABUSE_TESTING.md`, with the runnable k6 scenario at `load/k6/statement-service-load.js`.

Production deployment, rollback, migration, secret-rotation, and incident procedures are documented in `docs/PRODUCTION_RUNBOOK.md`.

## Audit Logging Policy

Audit writes are required by default. Each audit event is saved in a separate `REQUIRES_NEW` transaction with bounded synchronous retries. If the write still fails, the API returns `503 Service Unavailable` instead of silently dropping the event. Set `AUDIT_REQUIRED=false` only for an explicit best-effort deployment where audit loss is acceptable after retries.

## Retry And Timeout Strategy

The S3 client has explicit connection, socket/read, whole-call, and per-attempt timeouts. Retries are bounded with the AWS SDK default retry condition and backoff strategy. This retries transient network errors, throttling, and 5xx responses, while permanent client/auth failures such as 400 and 403 are not retried.

## Idempotency Strategy

Uploads are hashed with SHA-256 after being copied to a temporary file. The database has a unique constraint over customer, account, period, and hash. A repeated upload with the same file and metadata returns the existing statement. If two uploads race, the loser handles the unique constraint and returns the existing row.

## Transaction Boundary

The PDF is uploaded to S3 before opening the short metadata transaction. This prevents a database transaction from staying open during external I/O. If the metadata save fails after S3 upload succeeds, the service attempts a compensating S3 delete.

If that delete fails, the object is recorded in `orphaned_s3_objects` with enough metadata to investigate and retry. A scheduled reconciler retries queued deletes and scans the service-owned `customer/` S3 prefix for old objects that have no matching statement metadata row.

## Local MinIO

MinIO is used locally because it implements the S3 API without needing AWS credentials. It lets integration tests and Docker Compose exercise real object-storage behavior while remaining repeatable and cheap.

## Production Infrastructure

Production DB and object-storage controls are documented in `docs/PRODUCTION_DB_S3_CONTROLS.md`. For interview scope, the repo documents the required managed PostgreSQL, private encrypted object storage, lifecycle, access logging, and least-privilege IAM controls. A real deployment should enforce those controls through the target platform's infrastructure-as-code or policy system.

## Rate Limiting Trade-Off

Download-link rate limits are stored in the database table `rate_limit_windows`, so all application instances share the same counters. The default storage-failure behavior is fail-closed (`RATE_LIMIT_DOWNLOAD_LINK_FAILURE_MODE=DENY`) because presigned URL generation is security-sensitive. Set the failure mode to `ALLOW` only when availability is more important than strict limiting for a specific deployment.

## Security Notes

- Customer endpoints query by both statement ID and customer ID, so another customer's statement returns 404.
- Admin-only endpoints are protected with `SCOPE_admin`.
- The dev token endpoint is only active in explicit local development profiles and emits tokens with the configured issuer and audience.
- Logs and actuator health do not expose JWTs, presigned URLs, S3 secrets, or database passwords.
