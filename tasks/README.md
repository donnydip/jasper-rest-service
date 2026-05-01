# Improvement Tasks — Index

> **Project**: `jasper-service` (`com.devsec`)  
> **Created**: 2026-05-01  
> **Status Legend**: `[ ]` Todo · `[/]` In Progress · `[x]` Done

Each task file is scoped to a **distinct set of source files** so multiple AI agents can work in parallel without editing the same file.

## Task Files

| # | File | Focus Area | Tasks | Primary Files Touched |
|---|------|-----------|-------|----------------------|
| 1 | [task-01-security.md](./task-01-security.md) | 🔴 Security & Auth | SEC-01 → SEC-04 | `security/ApiKeyAuthFilter.java`, `security/SecurityConfig.java`, new files in `security/` |
| 2 | [task-02-validation-errors.md](./task-02-validation-errors.md) | 🟠 Input Validation & Error Handling | SEC-05, REL-01, REL-04 | `dto/ReportRequest.java`, `controller/AsyncReportController.java`, new `exception/GlobalExceptionHandler.java` |
| 3 | [task-03-service-core.md](./task-03-service-core.md) | 🟠🟡 Service Layer & Performance | REL-02, REL-03, REL-05, PERF-01 → PERF-05 | `service/AsyncReportService.java`, `config/DataSourceProperties.java`, `jobs/ReportJobListener.java` |
| 4 | [task-04-devops.md](./task-04-devops.md) | 🟢 DevOps & Configuration | SEC-03, REL-06, CODE-06, CODE-07, CODE-10 | `application.properties`, `Dockerfile`, `docker-compose.yml`, `.env.example` |
| 5 | [task-05-cleanup-testing.md](./task-05-cleanup-testing.md) | 🟢 Code Cleanup & Testing | CODE-01 → CODE-05, CODE-08, CODE-09 | `controller/ReportController.java`, `service/ReportService.java`, `pom.xml`, `src/test/`, new config files |

## Dependency Order

```
task-01 (Security)  ──┐
                      ├──► task-03 (Service Core)  ──► task-05 (Cleanup & Testing)
task-02 (Validation) ─┘
task-04 (DevOps)     ─── (independent, can run anytime)
```

- **task-01** and **task-02** can run **in parallel** — they touch completely different files.
- **task-04** can run **in parallel with anything** — only touches config/Docker files.
- **task-03** should run **after task-01 and task-02** — it heavily modifies `AsyncReportService.java` which overlaps with validation changes.
- **task-05** should run **last** — it cleans up dead code and writes tests against the final codebase.

## File Ownership Map

This table shows which agent "owns" each source file to prevent conflicts:

| Source File | Owner Task |
|------------|-----------|
| `security/ApiKeyAuthFilter.java` | task-01 |
| `security/SecurityConfig.java` | task-01 |
| `dto/ReportRequest.java` | task-02 |
| `controller/AsyncReportController.java` | task-02 |
| `service/AsyncReportService.java` | task-03 |
| `config/DataSourceProperties.java` | task-03 |
| `jobs/ReportJobListener.java` | task-03 |
| `config/RedisConfig.java` | task-03 |
| `application.properties` | task-04 |
| `Dockerfile` | task-04 |
| `docker-compose.yml` | task-04 |
| `controller/ReportController.java` | task-05 |
| `service/ReportService.java` | task-05 |
| `pom.xml` | task-05 |
| `src/test/**` | task-05 |
