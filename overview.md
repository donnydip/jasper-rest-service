# Jasper Report REST Service — Project Overview

## 1. Project Summary

| Attribute         | Value                                                |
| ----------------- | ---------------------------------------------------- |
| **Name**          | `jasper-service`                                     |
| **Group ID**      | `com.devsec`                                         |
| **Version**       | `0.0.1-SNAPSHOT`                                     |
| **Description**   | A centralized REST API microservice that generates JasperReports on behalf of multiple client applications. |
| **Java Version**  | 21                                                   |
| **Framework**     | Spring Boot 4.0.1                                    |
| **Build Tool**    | Maven (with Maven Wrapper `mvnw`)                    |
| **Report Engine** | JasperReports 6.21.3                                 |
| **Database**      | PostgreSQL 16 (multiple named connections)            |
| **Cache/Queue**   | Redis 7 (Pub/Sub for async job dispatch)              |
| **API Docs**      | SpringDoc OpenAPI 2.5.0 (Swagger UI at `/docs`)      |
| **Auth**          | Spring Security — Bearer token (placeholder JWT)      |
| **Containerization** | Docker + Docker Compose                           |

---

## 2. Architecture Overview

```
┌─────────────────┐        POST /api/v2/report/async        ┌──────────────────────────┐
│  Client App      │ ──────────────────────────────────────► │  AsyncReportController   │
│  (Laravel, etc.) │        (Bearer token required)          │  (REST Controller)       │
└─────────────────┘                                          └────────────┬─────────────┘
                                                                          │
                                                        1. Validate credentialKey
                                                        2. Store job in Redis (PENDING)
                                                        3. Publish jobId to Redis channel
                                                                          │
                                                                          ▼
                                                             ┌────────────────────────┐
                                                             │  Redis (Pub/Sub)       │
                                                             │  Channel: report-jobs  │
                                                             │  Hash: job:{jobId}     │
                                                             └────────────┬───────────┘
                                                                          │
                                                              Message received
                                                                          │
                                                                          ▼
                                                             ┌────────────────────────┐
                                                             │  ReportJobListener     │
                                                             │  (Redis Subscriber)    │
                                                             └────────────┬───────────┘
                                                                          │
                                                          Calls processReport(jobId)
                                                                          │
                                                                          ▼
                                                             ┌────────────────────────┐
                                                             │  AsyncReportService    │
                                                             │                        │
                                                             │  1. Read request JSON  │
                                                             │  2. Create JDBC conn   │
                                                             │  3. Compile .jrxml     │
                                                             │  4. Fill report (DB)   │
                                                             │  5. Export to format   │
                                                             │  6. Save file to disk  │
                                                             │  7. Update Redis status│
                                                             └────────────────────────┘
```

### Key Design Decisions

- **Multi-tenant database connections**: The service does NOT use a single database. It maintains a **map of named datasource credentials** (`web001`, `web002`, etc.) configured in `application.properties`. Each report request specifies which connection to use via `credentialKey`.
- **Asynchronous processing**: Report generation is decoupled via **Redis Pub/Sub**. The API returns immediately with a `jobId`, and a background listener processes the report.
- **One-time download**: Generated report files are **deleted from disk** after the first successful download.
- **Two API versions exist**: V1 (`/api/report/{name}`) is a simple synchronous endpoint with hardcoded sample data. V2 (`/api/v2/report/...`) is the production async pipeline.

---

## 3. Directory Structure

```
jasper-rest-service/
├── Dockerfile                          # Dev container (JDK 21 + Maven)
├── docker-compose.yml                  # PostgreSQL + Redis + App
├── pom.xml                             # Maven project descriptor
├── mvnw / mvnw.cmd                     # Maven wrapper scripts
├── src/
│   ├── main/
│   │   ├── java/com/devsec/jasperservice/
│   │   │   ├── JasperServiceApplication.java       # Spring Boot entry point
│   │   │   ├── config/
│   │   │   │   ├── DataSourceProperties.java       # Multi-tenant DB credential mapping
│   │   │   │   └── RedisConfig.java                # Redis Pub/Sub listener wiring
│   │   │   ├── controller/
│   │   │   │   ├── ReportController.java           # V1 sync endpoint (sample data)
│   │   │   │   └── AsyncReportController.java      # V2 async endpoints (production)
│   │   │   ├── dto/
│   │   │   │   ├── ReportRequest.java              # Inbound request DTO
│   │   │   │   ├── ReportResponse.java             # Job submission response DTO
│   │   │   │   └── StatusResponse.java             # Job status polling DTO
│   │   │   ├── jobs/
│   │   │   │   └── ReportJobListener.java          # Redis message listener (worker)
│   │   │   ├── security/
│   │   │   │   ├── ApiKeyAuthFilter.java           # Bearer token extraction filter
│   │   │   │   └── SecurityConfig.java             # Security rules & filter chain
│   │   │   └── service/
│   │   │       ├── ReportService.java              # V1 sync report generator
│   │   │       └── AsyncReportService.java         # V2 async report generator (core)
│   │   └── resources/
│   │       ├── application.properties              # App configuration
│   │       ├── reports/
│   │       │   └── Simple_Report.jrxml             # Sample Jasper template
│   │       └── static/
│   │           └── openapi.yaml                    # Hand-written OpenAPI 3.0 spec
│   └── test/
│       └── java/.../JasperServiceApplicationTests.java  # Placeholder context load test
```

---

## 4. Detailed File Descriptions

### 4.1 Entry Point

#### `JasperServiceApplication.java`
- **Package**: `com.devsec.jasperservice`
- Standard Spring Boot `@SpringBootApplication` main class. No customization.

---

### 4.2 Configuration Layer

#### `DataSourceProperties.java`
- **Package**: `com.devsec.jasperservice.config`
- **Annotation**: `@Configuration`, `@ConfigurationProperties(prefix = "datasource")`
- Binds all `datasource.connections.*` properties into a `Map<String, Credentials>`.
- `Credentials` inner class holds: `url`, `username`, `password`, `driverClassName`.
- **Usage**: `AsyncReportService` looks up a `Credentials` object by `credentialKey` from the incoming request, then opens a raw JDBC connection via `DriverManager`.

**Example property mapping**:
```properties
datasource.connections.web001.url=jdbc:postgresql://host:5432/db
datasource.connections.web001.username=user
datasource.connections.web001.password=pass
datasource.connections.web001.driver-class-name=org.postgresql.Driver
```
→ `getConnections().get("web001")` returns the `Credentials` object.

#### `RedisConfig.java`
- **Package**: `com.devsec.jasperservice.config`
- Defines the Redis Pub/Sub infrastructure:
  - `MessageListenerAdapter` wrapping `ReportJobListener`
  - `RedisMessageListenerContainer` subscribing to channel `"report-jobs"`
- The default `handleMessage(String message)` method on `ReportJobListener` is invoked automatically by Spring's `MessageListenerAdapter` when a message is published.

---

### 4.3 Controller Layer

#### `ReportController.java` — V1 (Synchronous, Legacy/Demo)
- **Package**: `com.devsec.jasperservice.controller`
- **Base Path**: `/api/report`
- **Endpoints**:
  | Method | Path                   | Description                     | Auth    |
  | ------ | ---------------------- | ------------------------------- | ------- |
  | GET    | `/api/report/{reportName}` | Generate & download PDF inline | Public  |
- Returns `byte[]` as `application/pdf` with `Content-Disposition: attachment`.
- Delegates to `ReportService` which uses **hardcoded sample data** (not a database).

#### `AsyncReportController.java` — V2 (Asynchronous, Production)
- **Package**: `com.devsec.jasperservice.controller`
- **Base Path**: `/api/v2/report`
- **Endpoints**:

  | Method | Path                              | Description                                            | Auth          |
  | ------ | --------------------------------- | ------------------------------------------------------ | ------------- |
  | POST   | `/api/v2/report/async`            | Submit a report generation job. Returns `202 Accepted`. | Bearer token  |
  | GET    | `/api/v2/report/status/{jobId}`   | Poll job status (`PENDING`/`PROCESSING`/`COMPLETE`/`FAILED`). | Bearer token  |
  | GET    | `/api/v2/report/download/{jobId}` | Download the generated report file (one-time).          | Bearer token  |

- **Submit flow**:
  1. Calls `asyncReportService.queueReport(request)` → returns `jobId`.
  2. Returns `ReportResponse` with `jobId` and `statusUrl`.

- **Status flow**:
  1. Reads Redis hash `job:{jobId}` for `status` and `error`.
  2. If `COMPLETE`, includes `downloadUrl` in response.

- **Download flow**:
  1. Checks job status is `COMPLETE`.
  2. Reads original `ReportRequest` JSON from Redis to determine filename and content type.
  3. Streams file as response.
  4. **Deletes the file and Redis job data** after serving.

- **Content-Type mapping** (`getContentType` method):
  | Format | Content-Type |
  | ------ | ------------ |
  | PDF    | `application/pdf` |
  | XLSX   | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
  | CSV    | `text/csv` |
  | DOCX   | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |

---

### 4.4 DTO Layer

#### `ReportRequest.java`
- Fields:
  | Field              | Type                    | Required | Default | Description |
  | ------------------ | ----------------------- | -------- | ------- | ----------- |
  | `jrxmlFileName`    | `String`                | Yes      | —       | Template name without `.jrxml` extension |
  | `outputFileName`   | `String`                | Yes      | —       | Desired output file base name |
  | `outputFormat`     | `OutputFormat` enum     | No       | `PDF`   | One of: `PDF`, `XLSX`, `CSV`, `DOCX` |
  | `appType`          | `String`                | No       | —       | Identifier for the calling application |
  | `credentialKey`    | `String`                | Yes      | —       | Key referencing a named datasource |
  | `jasperParameters` | `Map<String, Object>`   | No       | —       | Parameters passed to the Jasper template |

#### `ReportResponse.java`
- Returned on job submission.
- Fields: `jobId` (UUID string), `statusUrl` (auto-constructed as `/api/v2/report/status/{jobId}`).

#### `StatusResponse.java`
- Returned on status polling.
- Fields: `status` (enum: `PENDING`, `PROCESSING`, `COMPLETE`, `FAILED`), `message`, `downloadUrl` (only when `COMPLETE`).

---

### 4.5 Service Layer

#### `ReportService.java` — V1 (Demo only)
- **Package**: `com.devsec.jasperservice.service`
- Loads `.jrxml` from classpath (`classpath:reports/{name}.jrxml`).
- Uses hardcoded `SampleData` (inner class with `name` and `country` fields) as a `JRBeanCollectionDataSource`.
- Compiles, fills with static parameters (`ReportTitle`, `Author`), and exports to PDF `byte[]`.
- **Not connected to any database.**

#### `AsyncReportService.java` — V2 (Production core)
- **Package**: `com.devsec.jasperservice.service`
- **Dependencies**: `StringRedisTemplate`, `ObjectMapper`, `DataSourceProperties`
- **Output directory**: Configured via `report.output.dir` property (default: `/app/generated-reports`). Created on startup.

**Key methods**:

| Method | Description |
| ------ | ----------- |
| `queueReport(ReportRequest)` | Validates `credentialKey`, generates UUID `jobId`, stores request JSON + `PENDING` status in Redis hash `job:{jobId}`, publishes `jobId` to `report-jobs` channel. Returns `jobId`. |
| `processReport(String jobId)` | Sets status to `PROCESSING`. Reads request from Redis. Opens JDBC connection via `createDbConnection()`. Loads `.jrxml` from classpath (`/reports/{name}.jrxml`). Compiles + fills report using the DB connection and `jasperParameters`. Exports to the requested format. Saves file to disk. Sets status to `COMPLETE` with `filePath` in Redis. |
| `getReportFile(String jobId)` | Returns a `UrlResource` pointing to the generated file on disk. |
| `deleteReportFile(String jobId)` | Deletes the file from disk AND the Redis hash `job:{jobId}`. |
| `createDbConnection(String credentialKey)` | Private. Looks up `Credentials` from `DataSourceProperties`, loads JDBC driver via `Class.forName()`, returns `DriverManager.getConnection()`. |

**Export logic** (in `processReport`):
- `PDF` → `JasperExportManager.exportReportToPdfFile()`
- `XLSX` → `JRXlsxExporter`
- `CSV` → `JRCsvExporter`
- `DOCX` → `JRDocxExporter`

---

### 4.6 Job Processing Layer

#### `ReportJobListener.java`
- **Package**: `com.devsec.jasperservice.jobs`
- Implements `MessageListener`.
- Subscribed to Redis channel `report-jobs` (wired in `RedisConfig`).
- `onMessage()` extracts the `jobId` string from the Redis message body, then calls `asyncReportService.processReport(jobId)`.
- Wraps processing in a try-catch to log unhandled errors.

---

### 4.7 Security Layer

#### `ApiKeyAuthFilter.java`
- **Package**: `com.devsec.jasperservice.security`
- Extends `OncePerRequestFilter`.
- Extracts `Authorization: Bearer <token>` header.
- **Current implementation**: Accepts **any non-empty token** as valid (placeholder — no actual JWT validation).
- On valid token, sets `SecurityContext` with principal `"apiUser"` and authority `ROLE_API_USER`.

#### `SecurityConfig.java`
- **Package**: `com.devsec.jasperservice.security`
- CSRF disabled (stateless API).
- Session management: `STATELESS`.
- **Authorization rules**:
  | Path Pattern        | Access         |
  | ------------------- | -------------- |
  | `/docs/**`          | Permit all     |
  | `/openapi.yaml`     | Permit all     |
  | `/api-docs/**`      | Permit all     |
  | `/api/report/**`    | Permit all (V1 legacy) |
  | `/api/v2/**`        | Authenticated  |
  | Everything else     | Permit all     |
- Registers `ApiKeyAuthFilter` before `UsernamePasswordAuthenticationFilter`.

---

### 4.8 Report Templates

#### `Simple_Report.jrxml`
- Located at: `src/main/resources/reports/Simple_Report.jrxml`
- A simple JasperReport template with:
  - **Parameters**: `ReportTitle` (String), `Author` (String)
  - **Fields**: `name` (String), `country` (String)
  - **Layout**: Title band (report title + author), column header band (Name | Country), detail band (data rows)
  - **Page size**: A4 (595 x 842 points)
- Used by both V1 (with sample data) and V2 (with DB-sourced data via SQL in the template or passed parameters).

---

### 4.9 OpenAPI Specification

- **File**: `src/main/resources/static/openapi.yaml`
- **Served at**: `http://localhost:8080/openapi.yaml`
- **Swagger UI**: `http://localhost:8080/docs`
- Documents all V2 endpoints with schemas for `ReportRequest`, `ReportResponse`, `StatusResponse`.
- Security scheme: `BearerAuth` (type: http, scheme: bearer, format: JWT).

---

## 5. Data Flow — Complete Async Report Lifecycle

```
Client                     Controller              Redis                  Listener              Service                 Filesystem
  │                           │                      │                      │                      │                      │
  │ POST /api/v2/report/async │                      │                      │                      │                      │
  │ ─────────────────────────►│                      │                      │                      │                      │
  │                           │ queueReport()         │                      │                      │                      │
  │                           │─────────────────────►│ HSET job:{id}        │                      │                      │
  │                           │                      │ status=PENDING       │                      │                      │
  │                           │                      │ request={json}       │                      │                      │
  │                           │ PUBLISH report-jobs   │                      │                      │                      │
  │                           │─────────────────────►│─────────────────────►│                      │                      │
  │ ◄──── 202 {jobId} ────── │                      │                      │                      │                      │
  │                           │                      │                      │ processReport(jobId)  │                      │
  │                           │                      │                      │─────────────────────►│                      │
  │                           │                      │ HSET status=PROCESSING                      │                      │
  │                           │                      │◄─────────────────────────────────────────── │                      │
  │                           │                      │                      │                      │ Compile .jrxml        │
  │                           │                      │                      │                      │ Open JDBC connection  │
  │                           │                      │                      │                      │ Fill report with DB   │
  │                           │                      │                      │                      │ Export to format      │
  │                           │                      │                      │                      │─────────────────────►│
  │                           │                      │                      │                      │ Save file             │
  │                           │                      │ HSET status=COMPLETE │                      │                      │
  │                           │                      │ HSET filePath=...    │                      │                      │
  │                           │                      │◄─────────────────────────────────────────── │                      │
  │                           │                      │                      │                      │                      │
  │ GET /status/{jobId}       │                      │                      │                      │                      │
  │ ─────────────────────────►│ HGETALL job:{id}     │                      │                      │                      │
  │                           │─────────────────────►│                      │                      │                      │
  │ ◄── {status:COMPLETE} ── │                      │                      │                      │                      │
  │                           │                      │                      │                      │                      │
  │ GET /download/{jobId}     │                      │                      │                      │                      │
  │ ─────────────────────────►│ getReportFile()      │                      │                      │                      │
  │                           │──────────────────────────────────────────────────────────────────►│ Read file             │
  │ ◄── file bytes ────────── │                      │                      │                      │                      │
  │                           │ deleteReportFile()    │                      │                      │                      │
  │                           │─────────────────────►│ DEL job:{id}         │                      │ Delete file           │
  │                           │──────────────────────────────────────────────────────────────────►│                      │
```

---

## 6. Redis Data Model

All job state is stored in Redis hashes with key `job:{jobId}`.

| Hash Field | Written When       | Value                                       |
| ---------- | ------------------ | ------------------------------------------- |
| `status`   | Queue / Process    | `PENDING` → `PROCESSING` → `COMPLETE`/`FAILED` |
| `request`  | Queue              | Full JSON serialization of `ReportRequest`  |
| `filePath` | Process (complete) | Absolute file path on disk                  |
| `error`    | Process (failed)   | Exception message string                    |

- The entire hash `job:{jobId}` is **deleted** after a successful download via `deleteReportFile()`.
- Job dispatch uses Redis Pub/Sub channel `report-jobs`.

---

## 7. Configuration Reference

### `application.properties`

| Property | Description | Example |
| -------- | ----------- | ------- |
| `spring.application.name` | App name | `jasper-service` |
| `spring.datasource.url` | Main Spring datasource (JPA) | `jdbc:postgresql://postgres:5432/jasperservice` |
| `spring.datasource.username` | Main DB user | `${POSTGRES_USER}` |
| `spring.datasource.password` | Main DB password | `${POSTGRES_PASSWORD}` |
| `datasource.connections.{key}.url` | Named datasource JDBC URL | `jdbc:postgresql://host:5432/db` |
| `datasource.connections.{key}.username` | Named datasource user | `user_app1` |
| `datasource.connections.{key}.password` | Named datasource password | `pass_app1` |
| `datasource.connections.{key}.driver-class-name` | JDBC driver class | `org.postgresql.Driver` |
| `spring.data.redis.host` | Redis host | `${SPRING_REDIS_HOST}` |
| `spring.data.redis.port` | Redis port | `${SPRING_REDIS_PORT}` |
| `spring.jasper.reports.resource-location` | JRXML template directory | `file:/app/src/main/resources/reports/` |
| `report.output.dir` | Generated report output directory | `/app/generated-reports` |
| `springdoc.swagger-ui.path` | Swagger UI path | `/docs` |
| `springdoc.swagger-ui.url` | OpenAPI spec URL for Swagger UI | `/openapi.yaml` |
| `springdoc.api-docs.path` | JSON API docs path | `/api-docs` |

### Docker Compose Environment Variables

| Variable | Used By | Description |
| -------- | ------- | ----------- |
| `POSTGRES_USER` | postgres, jasper-service | Database username |
| `POSTGRES_PASSWORD` | postgres, jasper-service | Database password |
| `POSTGRES_DB` | postgres, jasper-service | Database name |
| `POSTGRES_HOST_PORT` | postgres | Host port mapping (default: 5432) |
| `REDIS_HOST_PORT` | redis | Host port mapping (default: 6379) |
| `JASPER_SERVICE_HOST_PORT` | jasper-service | Host port mapping (default: 8080) |
| `SPRING_REDIS_HOST` | jasper-service | Redis hostname (set to `redis`) |
| `SPRING_REDIS_PORT` | jasper-service | Redis port (set to `6379`) |

---

## 8. Deployment & Running

### Docker Compose (Development)

The `docker-compose.yml` sets up three services: `postgres`, `redis`, and `jasper-service`.

**Important**: The `jasper-service` container runs `tail -f /dev/null` (idle) and mounts the source code as a volume (`.:/app`). This means the application is **NOT started automatically**. You must exec into the container and run Maven manually:

```bash
# Start all services
docker compose up -d

# Build and run the app inside the container
docker compose exec jasper-service mvn spring-boot:run
```

### Dockerfile

- Base image: `eclipse-temurin:21-jdk-jammy`
- Installs Maven via `apt-get`
- Working directory: `/app`
- Source code is bind-mounted (not copied) — this is a **development-only** setup.

---

## 9. Dependencies Summary

| Dependency | Purpose |
| ---------- | ------- |
| `spring-boot-starter-webmvc` | REST API framework |
| `spring-boot-starter-data-jpa` | JPA/Hibernate (main datasource) |
| `spring-boot-starter-data-redis` | Redis client + Pub/Sub support |
| `spring-boot-starter-security` | Bearer token authentication |
| `spring-boot-starter-validation` | Request validation |
| `spring-boot-starter-actuator` | Health checks & metrics |
| `spring-boot-devtools` | Hot reload in development |
| `postgresql` | PostgreSQL JDBC driver |
| `lombok` | Boilerplate reduction (annotation processor configured, but not actively used in current code) |
| `jasperreports:6.21.3` | Report compilation, filling, and export |
| `springdoc-openapi-starter-webmvc-ui:2.5.0` | Swagger UI + OpenAPI auto-docs |
| `spring-boot-starter-test` | JUnit 5 testing |

---

## 10. Adding a New Report Template

To add a new Jasper report:

1. Place the `.jrxml` file in `src/main/resources/reports/` (e.g., `Invoice_Report.jrxml`).
2. Ensure the template's SQL query or fields match the target database schema referenced by the `credentialKey`.
3. Call the async API with:
   ```json
   {
     "jrxmlFileName": "Invoice_Report",
     "outputFileName": "Invoice_2024",
     "outputFormat": "PDF",
     "credentialKey": "web001",
     "jasperParameters": {
       "StartDate": "2024-01-01",
       "EndDate": "2024-12-31"
     }
   }
   ```

## 11. Adding a New Database Connection

1. Add entries to `application.properties`:
   ```properties
   datasource.connections.web003.url=jdbc:postgresql://newhost:5432/newdb
   datasource.connections.web003.username=newuser
   datasource.connections.web003.password=newpass
   datasource.connections.web003.driver-class-name=org.postgresql.Driver
   ```
2. Reference `"web003"` as the `credentialKey` in API requests.

---

## 12. Known Limitations & TODOs

| Area | Issue |
| ---- | ----- |
| **Authentication** | `ApiKeyAuthFilter` accepts ANY non-empty Bearer token. No JWT validation is implemented. This is a placeholder. |
| **Error handling** | V1 controller uses `e.printStackTrace()`. No global exception handler (`@ControllerAdvice`) exists. |
| **Job expiry** | Redis job hashes have no TTL. Failed/unclaimed jobs will persist indefinitely. |
| **Scalability** | Report processing happens synchronously inside the Redis listener thread. For high volume, a proper task queue (e.g., Spring `@Async`, Celery-like pattern, or dedicated worker threads) would be needed. |
| **Dockerfile** | The Dockerfile is development-only (no JAR build, no multi-stage). Not production-ready. |
| **Lombok** | Lombok is declared as a dependency and configured in the compiler plugin, but no classes actually use Lombok annotations. |
| **Tests** | Only a single placeholder `contextLoads()` test exists. No unit or integration tests for services/controllers. |
| **V1 endpoint** | `ReportService` uses hardcoded sample data. It's likely a prototype/demo and not used in production. |
| **Main datasource** | `spring.datasource.*` is configured but JPA is not actively used by any entity/repository in the codebase. |

---

## 13. Improvement Tasks

See [`tasks/README.md`](./tasks/README.md) for the full prioritized improvement checklist split into **5 independent task files** designed for parallel AI agent execution:

| File | Focus | Tasks |
|------|-------|-------|
| [`task-01-security.md`](./tasks/task-01-security.md) | Security & Auth | SEC-01, SEC-02, SEC-04 |
| [`task-02-validation-errors.md`](./tasks/task-02-validation-errors.md) | Input Validation & Error Handling | SEC-05, REL-01, REL-04 |
| [`task-03-service-core.md`](./tasks/task-03-service-core.md) | Service Layer & Performance | REL-02, REL-03, REL-05, PERF-01→05 |
| [`task-04-devops.md`](./tasks/task-04-devops.md) | DevOps & Configuration | SEC-03, REL-06, CODE-06, CODE-07, CODE-10 |
| [`task-05-cleanup-testing.md`](./tasks/task-05-cleanup-testing.md) | Code Cleanup & Testing | CODE-01→05, CODE-08, CODE-09 |

