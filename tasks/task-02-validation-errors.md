# Task 02 — Input Validation & Error Handling

> **Priority**: 🟠 High  
> **Status**: `[ ]` Not Started  
> **Files Owned**: `dto/ReportRequest.java`, `controller/AsyncReportController.java`, new `exception/GlobalExceptionHandler.java`  
> **No conflict with**: task-01, task-04  
> **Depends on**: Nothing  
> **Blocked by this**: task-03 (service layer references these DTOs)

---

## Context

The application has no input validation and no structured error responses. Missing required fields cause NullPointerExceptions deep in the service layer. Error responses are bare HTTP 500 with no body. The `ReportRequest` DTO has no Jakarta Validation annotations.

**Relevant source files to read before starting**:
- [overview.md](../overview.md) — Section 4.3 (Controller Layer), Section 4.4 (DTO Layer)
- `src/main/java/com/devsec/jasperservice/dto/ReportRequest.java`
- `src/main/java/com/devsec/jasperservice/dto/ReportResponse.java`
- `src/main/java/com/devsec/jasperservice/dto/StatusResponse.java`
- `src/main/java/com/devsec/jasperservice/controller/AsyncReportController.java`

---

## Tasks

### SEC-05: Sanitize user inputs (`credentialKey`, `jrxmlFileName`)

- [ ] **Status**: Todo
- **File**: `dto/ReportRequest.java`
- **Problem**: `jrxmlFileName` is used directly in a classpath resource path (`/reports/{name}.jrxml`). A value like `../../etc/passwd` could allow path traversal. `credentialKey` is used as a map key but should still be validated.
- **Action**:
  1. Add Jakarta Validation annotations to `ReportRequest`:
     ```java
     import jakarta.validation.constraints.NotBlank;
     import jakarta.validation.constraints.NotNull;
     import jakarta.validation.constraints.Pattern;

     @NotBlank
     @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "jrxmlFileName must be alphanumeric with underscores/hyphens only")
     private String jrxmlFileName;

     @NotBlank
     private String outputFileName;

     @NotNull
     private OutputFormat outputFormat = OutputFormat.PDF;

     @NotBlank
     @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "credentialKey must be alphanumeric with underscores/hyphens only")
     private String credentialKey;
     ```
  2. Ensure `outputFileName` is also sanitized (used in `Content-Disposition` header and file path).

---

### REL-04: Add `@Valid` to controller endpoints

- [ ] **Status**: Todo
- **File**: `controller/AsyncReportController.java`
- **Problem**: Even with annotations on `ReportRequest`, validation won't trigger without `@Valid` on the controller method parameter.
- **Action**:
  1. Add `@Valid` annotation to the `submitReport` method:
     ```java
     import jakarta.validation.Valid;

     @PostMapping("/async")
     public ResponseEntity<ReportResponse> submitReport(@Valid @RequestBody ReportRequest request) {
         // ...
     }
     ```
  2. The `GlobalExceptionHandler` (REL-01 below) will catch `MethodArgumentNotValidException` and return a `400` response.

---

### REL-01: Add global exception handler

- [ ] **Status**: Todo
- **File**: New `exception/GlobalExceptionHandler.java`
- **Problem**: V1 uses `e.printStackTrace()`. V2 returns bare `500 Internal Server Error` with no response body. No consistent error format across the API.
- **Action**:
  1. Create a new package `com.devsec.jasperservice.exception`.
  2. Create `GlobalExceptionHandler.java`:
     ```java
     package com.devsec.jasperservice.exception;

     import org.springframework.http.HttpStatus;
     import org.springframework.http.ResponseEntity;
     import org.springframework.web.bind.MethodArgumentNotValidException;
     import org.springframework.web.bind.annotation.ControllerAdvice;
     import org.springframework.web.bind.annotation.ExceptionHandler;

     import java.time.Instant;
     import java.util.LinkedHashMap;
     import java.util.Map;
     import java.util.stream.Collectors;

     @ControllerAdvice
     public class GlobalExceptionHandler {

         @ExceptionHandler(MethodArgumentNotValidException.class)
         public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
             Map<String, Object> body = new LinkedHashMap<>();
             body.put("timestamp", Instant.now().toString());
             body.put("status", 400);
             body.put("error", "Validation Failed");
             body.put("details", ex.getBindingResult().getFieldErrors().stream()
                 .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                 .collect(Collectors.toList()));
             return ResponseEntity.badRequest().body(body);
         }

         @ExceptionHandler(IllegalArgumentException.class)
         public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
             return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
         }

         @ExceptionHandler(java.io.FileNotFoundException.class)
         public ResponseEntity<Map<String, Object>> handleNotFound(java.io.FileNotFoundException ex) {
             return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
         }

         @ExceptionHandler(Exception.class)
         public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
             return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
         }

         private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
             Map<String, Object> body = new LinkedHashMap<>();
             body.put("timestamp", Instant.now().toString());
             body.put("status", status.value());
             body.put("error", status.getReasonPhrase());
             body.put("message", message);
             return ResponseEntity.status(status).body(body);
         }
     }
     ```
  3. This automatically handles validation errors from `@Valid` (SEC-05 + REL-04 above).

---

## Verification

After completing all tasks:
1. `POST /api/v2/report/async` with missing `jrxmlFileName` → `400` with `{ "error": "Validation Failed", "details": ["jrxmlFileName: must not be blank"] }`
2. `POST /api/v2/report/async` with `jrxmlFileName: "../../etc/passwd"` → `400` with pattern violation message
3. `POST /api/v2/report/async` with invalid `credentialKey` → `400` with `"Invalid credentialKey"` message
4. Any unhandled server error → `500` with `{ "error": "Internal Server Error", "message": "An unexpected error occurred" }`
