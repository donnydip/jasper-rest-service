# Task 03 — Service Layer & Performance

> **Priority**: 🟠 High + 🟡 Medium  
> **Status**: `[ ]` Not Started  
> **Files Owned**: `service/AsyncReportService.java`, `config/DataSourceProperties.java`, `jobs/ReportJobListener.java`, `config/RedisConfig.java`, new `config/AsyncConfig.java`  
> **Depends on**: task-02 (DTO changes should be finalized first)

---

## Context

`AsyncReportService` is the core of the application — it queues, processes, and manages report jobs. It currently has reliability and performance issues: no Redis TTL, synchronous processing on the listener thread, raw JDBC without pooling, and no compiled report caching.

Read before starting: [overview.md](../overview.md) — Sections 4.5, 4.6, 5, 6.

---

## Tasks

### REL-02: Add Redis job TTL / expiration
- [ ] **File**: `service/AsyncReportService.java`
- **Problem**: Redis hashes `job:{jobId}` never expire. Orphaned jobs leak memory.
- **Action**: After every status update, call `redisTemplate.expire("job:" + jobId, 24, TimeUnit.HOURS)`. Use shorter TTL for `COMPLETE` jobs.

### REL-03: Handle Redis connection failures gracefully
- [ ] **File**: `service/AsyncReportService.java`
- **Problem**: Redis down → uncaught `RedisConnectionFailureException` → raw 500.
- **Action**: Catch `RedisConnectionFailureException` in `queueReport()`, throw `ResponseStatusException(SERVICE_UNAVAILABLE)`.

### REL-05: Fix JDBC connection leak risk
- [ ] **File**: `service/AsyncReportService.java`
- **Problem**: Raw `Connection` via `DriverManager`. Fragile pattern.
- **Action**: Resolved by PERF-02 (HikariCP). Otherwise ensure try-with-resources is maintained.

### PERF-01: Offload report processing to worker threads
- [ ] **Files**: `jobs/ReportJobListener.java`, new `config/AsyncConfig.java`
- **Problem**: `onMessage()` runs `processReport()` synchronously on Redis subscriber thread, blocking all messages.
- **Action**: Create `AsyncConfig` with `@EnableAsync` + `ThreadPoolTaskExecutor` (core=2, max=5, queue=25). Add `@Async("reportExecutor")` to `processReport()`.

### PERF-02: Add connection pooling for named datasources
- [ ] **Files**: `config/DataSourceProperties.java`, `service/AsyncReportService.java`
- **Problem**: New JDBC connection per request via `DriverManager`. No pooling.
- **Action**: Add `Map<String, HikariDataSource>` pool factory in `DataSourceProperties`. Replace `createDbConnection()` to use `dataSourceProperties.getDataSource(key).getConnection()`.

### PERF-03: Cache compiled JasperReport objects
- [ ] **File**: `service/AsyncReportService.java`
- **Problem**: `.jrxml` compiled every request. CPU-intensive and wasteful.
- **Action**: Add `ConcurrentHashMap<String, JasperReport>` cache. Use `computeIfAbsent()` to compile once and reuse.

### PERF-04: Add concurrency limits for jobs
- [ ] **File**: `service/AsyncReportService.java`
- **Problem**: No limit on queued/concurrent jobs. Can exhaust resources.
- **Action**: ThreadPoolTaskExecutor from PERF-01 limits concurrency. Additionally check queue depth in `queueReport()` and return `429` when over limit.

### PERF-05: Replace `Class.forName()` + `DriverManager` pattern
- [ ] **File**: `service/AsyncReportService.java`
- **Problem**: Outdated JDBC 3.0 pattern. Modern drivers auto-register.
- **Action**: Resolved by PERF-02. Otherwise just remove `Class.forName()` line.

---

## Verification
1. Submit 3 jobs → verify parallel processing (different thread names in logs)
2. Check Redis TTL on job hash: `redis-cli TTL job:{id}`
3. Stop Redis → submit job → `503` JSON response
4. Submit 50+ jobs → `429` for overflow
5. Same template twice → second is faster (cached compilation)
