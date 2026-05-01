# Task 05 — Code Cleanup & Testing

> **Priority**: 🟢 Low  
> **Status**: `[ ]` Not Started  
> **Files Owned**: `controller/ReportController.java`, `service/ReportService.java`, `pom.xml`, `src/test/`, new `config/CorsConfig.java`  
> **No conflict with**: task-04  
> **Depends on**: task-01, task-02, task-03 (should run last — tests need final codebase)

---

## Context

The codebase has unused dependencies (Lombok configured but unused, JPA included but no entities), a legacy V1 endpoint with hardcoded data, no meaningful tests, and missing CORS/OpenAPI configurations.

Read before starting: [overview.md](../overview.md) — Sections 4.1-4.5, 9.

---

## Tasks

### CODE-01: Remove or refactor V1 endpoint
- [ ] **Files**: `controller/ReportController.java`, `service/ReportService.java`
- **Problem**: V1 uses hardcoded `SampleData` inner class. Not production-usable.
- **Action**: Delete both files, or convert to a `/api/health/report-test` smoke-test endpoint.

### CODE-02: Use Lombok or remove the dependency
- [ ] **Files**: `pom.xml`, all DTO classes
- **Problem**: Lombok declared + compiler plugin configured, but zero classes use it.
- **Action A** (use it): Add `@Data` to `ReportRequest`, `ReportResponse`, `StatusResponse`, `DataSourceProperties.Credentials`. Remove manual getters/setters.
- **Action B** (remove it): Delete Lombok from `pom.xml` `<dependencies>` and `<annotationProcessorPaths>`.
- **Note**: If choosing Action A, coordinate with task-02 owner since they own the DTO files. Best to just remove Lombok (Action B) to avoid conflicts.

### CODE-03: Remove unused JPA dependency or add entities
- [ ] **File**: `pom.xml`
- **Problem**: `spring-boot-starter-data-jpa` included but no `@Entity` or `@Repository` exists.
- **Action A** (remove): Delete the `spring-boot-starter-data-jpa` dependency and the `postgresql` runtime dependency if not needed elsewhere. Note: the async service uses raw JDBC via `DriverManager`, not JPA.
- **Action B** (use it): Create a `JobHistory` entity for persisting job audit records. This is a larger effort and should be a separate future task.
- **Recommendation**: Action A — remove JPA. The app uses raw JDBC for reports and Redis for job state. JPA adds unnecessary startup overhead.

### CODE-04: Add structured logging with correlation IDs
- [ ] **Files**: `controller/AsyncReportController.java` (coordinate with task-02), `security/ApiKeyAuthFilter.java` (coordinate with task-01)
- **Problem**: Only `AsyncReportService` and `ReportJobListener` have logging. Controllers and security are silent.
- **Action**: Add SLF4J `LOGGER` to owned files. Use MDC for jobId correlation:
  ```java
  import org.slf4j.MDC;
  MDC.put("jobId", jobId);
  // ... processing ...
  MDC.remove("jobId");
  ```
- **Note**: Since the primary files are owned by other tasks, this task should add logging only to files this task owns (ReportController, ReportService) or create a shared `LoggingInterceptor`.

### CODE-05: Write unit and integration tests
- [ ] **Files**: `src/test/java/com/devsec/jasperservice/`
- **Problem**: Only a placeholder `contextLoads()` test exists.
- **Action**: Create the following tests:
  - [ ] `AsyncReportServiceTest.java` — Unit test `queueReport()` with mocked `StringRedisTemplate`
  - [ ] `AsyncReportServiceTest.java` — Unit test `processReport()` with mocked Redis + in-memory DB
  - [ ] `AsyncReportControllerTest.java` — `@WebMvcTest` with `MockMvc` for all 3 endpoints
  - [ ] `ReportJobListenerTest.java` — Verify `onMessage()` delegates to service
  - [ ] `ApiKeyAuthFilterTest.java` — Test auth filter with valid/invalid/missing tokens
  - [ ] (Optional) Integration test with Testcontainers for Redis + PostgreSQL

### CODE-08: Add CORS configuration
- [ ] **File**: New `config/CorsConfig.java`
- **Problem**: No CORS config. Browser-based clients blocked.
- **Action**: Create `CorsConfig.java`:
  ```java
  @Configuration
  public class CorsConfig implements WebMvcConfigurer {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
          registry.addMapping("/api/**")
              .allowedOrigins("*") // Restrict in production
              .allowedMethods("GET", "POST", "OPTIONS")
              .allowedHeaders("*");
      }
  }
  ```
  Or make origins configurable via `application.properties` (coordinate with task-04).

### CODE-09: Add OpenAPI annotations to controllers
- [ ] **Files**: `controller/AsyncReportController.java` (coordinate with task-02 owner)
- **Problem**: OpenAPI spec is hand-written in `openapi.yaml`. Can drift from actual code.
- **Action**: Add `@Operation`, `@ApiResponse`, `@Parameter`, `@Schema` annotations. Let SpringDoc auto-generate the spec.
- **Note**: Since `AsyncReportController.java` is owned by task-02, this task should be done after task-02 completes, or coordinate with task-02 owner to include annotations during their changes.

---

## Verification
1. V1 endpoint removed → `GET /api/report/Simple_Report` → `404`
2. `mvn test` → all new tests pass
3. No Lombok or JPA in `mvn dependency:tree` output (if removed)
4. `curl -X OPTIONS http://localhost:8080/api/v2/report/async` → returns CORS headers
5. `GET /api-docs` → auto-generated OpenAPI JSON matches actual endpoints
