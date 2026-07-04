# jasper-service

Asynchronous JasperReports generation REST API built on Spring Boot 4. Clients submit a report
job, poll for status, and download the finished file (PDF, XLSX, CSV, or DOCX) once it's ready.
Report queries run against per-client named datasources rather than a single shared database.

## Architecture

```
POST /api/v2/report/async   ──► queue job in Redis, publish to "report-jobs" channel
                                  │
                                  ▼
                          ReportJobListener (Redis pub/sub subscriber)
                                  │
                                  ▼
                          AsyncReportService.processReport (@Async worker pool)
                                  │  fills a JasperReport against the named HikariCP datasource
                                  ▼
                          file written to report.output.dir, job status → COMPLETE

GET  /api/v2/report/status/{jobId}    ──► poll job status from Redis
GET  /api/v2/report/download/{jobId}  ──► atomically claim + stream the file, delete on close
```

- **Redis** is the job queue, status store, and pub/sub broker (job hashes expire automatically).
- **Per-client authorization**: each API key is scoped to a list of `allowedCredentialKeys` —
  a client can only run reports against the datasources it's been granted.
- **Named datasources**: each `credentialKey` (e.g. `web001`) maps to its own HikariCP-backed
  connection pool, configured independently of any default Spring datasource.

## Requirements

- Java 21
- Maven (or use the bundled `./mvnw`)
- Redis
- One or more PostgreSQL databases to report against (or any JDBC source)
- Docker + Docker Compose (optional, for local infra)

## Getting Started

1. Copy the environment template and fill in real values:
   ```bash
   cp .env.example .env
   ```
2. Start Redis/Postgres (and the app) via Docker Compose:
   ```bash
   docker compose up --build
   ```
   The app waits for Postgres and Redis to report healthy before starting, and exposes its own
   healthcheck on `/actuator/health`.
3. Or run locally against infra you already have running:
   ```bash
   ./mvnw spring-boot:run
   ```

## Authentication

All `/api/v2/**` endpoints require a bearer API key:

```
Authorization: Bearer <api-key>
```

API keys are stored in Redis under `apikey:<sha256(key)>` — the raw key is never persisted.
Each key record carries:

```json
{
  "clientName": "SomeClient",
  "allowedCredentialKeys": ["web001"],
  "active": true,
  "createdAt": 1735689600000
}
```

Requests to a `credentialKey` outside a client's `allowedCredentialKeys` are rejected with
`403 Forbidden`. Any endpoint not explicitly whitelisted (docs, Swagger UI, actuator health/info)
requires authentication — the security config is deny-by-default.

Rate limiting (`rate-limit.requests-per-minute`, default 10/min) is applied per API key (hashed)
or per client IP for unauthenticated requests, returning `429` with a `Retry-After` header.

## API

Interactive docs: `GET /docs` (Swagger UI), raw spec at `GET /openapi.yaml`.

| Method | Path | Description |
|---|---|---|
| POST | `/api/v2/report/async` | Queue a new report job. Returns `202` with a `jobId`. |
| GET  | `/api/v2/report/status/{jobId}` | Poll job status (`PENDING`, `PROCESSING`, `COMPLETE`, `FAILED`). |
| GET  | `/api/v2/report/download/{jobId}` | Download the finished file. One-time — the file is deleted after streaming. |

Example submit request:

```bash
curl -X POST http://localhost:8080/api/v2/report/async \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
        "jrxmlFileName": "Simple_Report",
        "outputFileName": "MyReport",
        "outputFormat": "PDF",
        "credentialKey": "web001",
        "jasperParameters": {}
      }'
```

`.jrxml` templates live in `src/main/resources/reports/`; `jrxmlFileName` refers to a template
in that directory (no path traversal — validated by a strict allow-list pattern).

## Configuration

Key properties in `src/main/resources/application.properties` (override via env vars):

| Property | Purpose |
|---|---|
| `datasource.connections.<key>.*` | Per-client named JDBC connection (url/username/password/driver) |
| `report.output.dir` | Where generated report files are written |
| `report.retention-hours` | Age (hours) after which orphaned/undownloaded report files are swept, default `24` |
| `rate-limit.requests-per-minute` / `rate-limit.enabled` | Rate limiting knobs |
| `management.endpoints.web.exposure.include` | Actuator endpoints exposed |

See `.env.example` for the full set of environment variables used by Docker Compose.

## Testing

```bash
./mvnw test
```

## Project Layout

```
src/main/java/com/devsec/jasperservice/
├── config/        # DataSourceProperties, AsyncConfig, RedisConfig, CorsConfig, LoggingInterceptor
├── controller/     # AsyncReportController (REST API)
├── dto/            # Request/response payloads
├── exception/       # GlobalExceptionHandler
├── jobs/            # ReportJobListener (Redis pub/sub consumer)
├── security/        # API key auth filter/service, rate limiting, security config
└── service/         # AsyncReportService (core report generation logic)
```

Design/implementation history and audit notes are tracked under [`tasks/`](./tasks/README.md).
