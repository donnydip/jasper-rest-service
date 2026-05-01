# Task 04 — DevOps & Configuration

> **Priority**: 🔴 Critical (SEC-03) + 🟢 Low  
> **Status**: `[ ]` Not Started  
> **Files Owned**: `application.properties`, `Dockerfile`, `docker-compose.yml`, new `.env.example`  
> **No conflict with**: All other task files  
> **Depends on**: Nothing (fully independent)

---

## Context

Configuration files have hardcoded credentials, the Dockerfile is dev-only (no JAR build), and Docker Compose requires manual app startup. No `.env.example` exists for onboarding.

Read before starting: [overview.md](../overview.md) — Sections 7, 8.

---

## Tasks

### SEC-03: Secure database credentials
- [ ] **File**: `application.properties`
- **Problem**: Named datasource passwords are hardcoded in plain text.
- **Action**: Replace hardcoded values with environment variable references:
  ```properties
  datasource.connections.web001.url=${DS_WEB001_URL:jdbc:postgresql://localhost:5432/db_app1}
  datasource.connections.web001.username=${DS_WEB001_USER:user_app1}
  datasource.connections.web001.password=${DS_WEB001_PASS}
  datasource.connections.web001.driver-class-name=org.postgresql.Driver
  ```
  Add corresponding env vars to `docker-compose.yml` and `.env.example`.

### REL-06: Configure actuator health checks
- [ ] **File**: `application.properties`
- **Problem**: `spring-boot-starter-actuator` is included but not configured. No health endpoint.
- **Action**: Add to `application.properties`:
  ```properties
  management.endpoints.web.exposure.include=health,info,metrics
  management.endpoint.health.show-details=always
  management.health.redis.enabled=true
  management.health.db.enabled=true
  ```

### CODE-06: Add `.env.example` file
- [ ] **File**: New `.env.example`
- **Problem**: Docker Compose references env vars from `.env` (gitignored). No template for new developers.
- **Action**: Create `.env.example`:
  ```env
  # PostgreSQL
  POSTGRES_USER=jasper
  POSTGRES_PASSWORD=changeme
  POSTGRES_DB=jasperservice
  POSTGRES_HOST_PORT=5432

  # Redis
  REDIS_HOST_PORT=6379

  # App
  JASPER_SERVICE_HOST_PORT=8080

  # Named Datasources
  DS_WEB001_URL=jdbc:postgresql://postgres:5432/db_for_app1
  DS_WEB001_USER=user_app1
  DS_WEB001_PASS=pass_app1
  ```

### CODE-07: Make Dockerfile production-ready
- [ ] **File**: `Dockerfile`
- **Problem**: Current Dockerfile installs Maven, expects source mounted as volume. Dev-only.
- **Action**: Create multi-stage build:
  ```dockerfile
  # Stage 1: Build
  FROM eclipse-temurin:21-jdk-jammy AS build
  WORKDIR /app
  COPY pom.xml .
  COPY .mvn .mvn
  COPY mvnw .
  RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
  COPY src src
  RUN ./mvnw clean package -DskipTests -B

  # Stage 2: Run
  FROM eclipse-temurin:21-jre-jammy
  WORKDIR /app
  COPY --from=build /app/target/*.jar app.jar
  RUN mkdir -p /app/generated-reports
  EXPOSE 8080
  ENTRYPOINT ["java", "-jar", "app.jar"]
  ```
  Keep the old Dockerfile as `Dockerfile.dev` if needed.

### CODE-10: Auto-start app in Docker Compose
- [ ] **File**: `docker-compose.yml`
- **Problem**: Container runs `tail -f /dev/null`. Requires manual `mvn spring-boot:run`.
- **Action**: For production, remove the `command` override and let the Dockerfile `ENTRYPOINT` handle startup. For dev, use a profile:
  ```yaml
  jasper-service:
    build: .
    container_name: jasper-service
    restart: unless-stopped
    ports:
      - "${JASPER_SERVICE_HOST_PORT:-8080}:8080"
    depends_on:
      - postgres
      - redis
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      - SPRING_DATASOURCE_USERNAME=${POSTGRES_USER}
      - SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}
      - SPRING_REDIS_HOST=redis
      - SPRING_REDIS_PORT=6379
  ```

---

## Verification
1. `cp .env.example .env` → edit values → `docker compose up --build` → app starts automatically
2. `curl http://localhost:8080/actuator/health` → returns `{"status":"UP","components":{"redis":...,"db":...}}`
3. No hardcoded passwords visible in `application.properties`
4. Docker image builds successfully with multi-stage (check image size is smaller — JRE only, no Maven/source)
