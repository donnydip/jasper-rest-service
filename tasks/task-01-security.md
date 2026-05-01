# Task 01 — Security & Authentication

> **Priority**: 🔴 Critical  
> **Status**: `[ ]` Not Started  
> **Files Owned**: `security/ApiKeyAuthFilter.java`, `security/SecurityConfig.java`, new files in `security/`  
> **No conflict with**: All other task files  
> **Depends on**: Nothing

---

## Context

The security layer is currently a placeholder. `ApiKeyAuthFilter` accepts any non-empty Bearer token without validation. There is no concept of client identity or permissions. All V2 endpoints are "authenticated" but the authentication is meaningless.

**Relevant source files to read before starting**:
- [overview.md](../overview.md) — Section 4.7 (Security Layer)
- `src/main/java/com/devsec/jasperservice/security/ApiKeyAuthFilter.java`
- `src/main/java/com/devsec/jasperservice/security/SecurityConfig.java`
- `src/main/resources/application.properties` (for datasource connection keys)

---

## Tasks

### SEC-01: Implement real JWT validation

- [ ] **Status**: Todo
- **File**: `security/ApiKeyAuthFilter.java`
- **Problem**: The filter accepts ANY non-empty Bearer token. No signature, expiry, or issuer check is performed.
- **Action**:
  1. Add `spring-security-oauth2-resource-server` dependency to `pom.xml` (coordinate with task-05 owner).
  2. Configure JWT decoder with a JWKS endpoint or a symmetric secret in `application.properties` (coordinate with task-04 owner).
  3. Replace the placeholder logic in `doFilterInternal()` with proper JWT validation:
     - Verify token signature
     - Check `exp` (expiration), `iss` (issuer), `aud` (audience) claims
     - Extract user/client identity from claims
  4. Alternatively, if using a simple API key scheme (not JWT), validate against a stored key list (see SEC-02).
- **Current placeholder code** (lines 27-32 of `ApiKeyAuthFilter.java`):
  ```java
  // ================== IMPORTANT ==================
  // IN A REAL APP, YOU WOULD VALIDATE THE JWT TOKEN HERE
  // For this example, we'll just accept any non-empty token.
  // ===============================================
  ```

---

### SEC-02: Add API key management per client app

- [ ] **Status**: Todo
- **Files**: New `security/ApiKeyService.java`, new `security/ApiKeyRepository.java` (or Redis-based)
- **Problem**: No concept of client identity. Any token accesses all datasources and all report templates.
- **Action**:
  1. Design an API key model with fields: `key`, `clientName`, `allowedCredentialKeys[]`, `isActive`, `createdAt`.
  2. Store API keys in either:
     - PostgreSQL (new `api_keys` table + JPA entity) — requires coordination with task-05 for JPA setup
     - Redis hash (simpler, fits existing infra)
  3. In `ApiKeyAuthFilter`, after validating the token, look up the API key and attach the allowed `credentialKeys` to the `SecurityContext`.
  4. In `AsyncReportService.queueReport()`, check that the requested `credentialKey` is in the authenticated client's allowed list.
- **Scoping note**: This task creates new files only. The `queueReport()` authorization check should be a simple guard that task-03 owner adds after this task is complete.

---

### SEC-04: Add rate limiting

- [ ] **Status**: Todo
- **Files**: New `security/RateLimitFilter.java` or new `config/RateLimitConfig.java`
- **Problem**: No throttling. A single client can flood the service with unlimited report jobs.
- **Action**:
  1. Add Bucket4j or resilience4j dependency to `pom.xml` (coordinate with task-05 owner).
  2. Create a rate limit filter or interceptor that:
     - Identifies the client (from API key after SEC-02, or from Bearer token)
     - Enforces a per-client rate limit (suggested: 10 requests/minute)
     - Returns `429 Too Many Requests` with `Retry-After` header when exceeded
  3. Register the filter in `SecurityConfig.java` (this file is owned by this task).
  4. Make rate limit values configurable via `application.properties` (coordinate with task-04 owner):
     ```properties
     rate-limit.requests-per-minute=10
     rate-limit.enabled=true
     ```

---

## Verification

After completing all tasks:
1. Sending a request without `Authorization` header → `401 Unauthorized`
2. Sending a request with an invalid/expired token → `401 Unauthorized`
3. Sending a request with a valid token but disallowed `credentialKey` → `403 Forbidden`
4. Sending more than 10 requests/minute → `429 Too Many Requests`
5. Sending a valid request with proper auth → `202 Accepted`
