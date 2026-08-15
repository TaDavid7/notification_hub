**Setup**
Used Spring Initializer - start.spring.io
Gradle - Kotlin
Java 17
Jar
Spring Boot 3.5.5
Group  com.david
Artifact notification-hub
Package name com.david.notification_hub

**Dependencies**
spring-boot-starter-web: HTTP API. Built in web server (Tomcat), auto converts between JSON and Java
Spring Data JPA + hibernate(JPA provider): Talks to database, so I don't need to write SQL
Validation - checks incoming data
Spring Boot Actuator - Adds a health check url. Hit /actuator/health and get up or down
flyware-core + flyway-database-postgresql - manages database structure
postgresql - translator between java and postgres
springdoc-openapi - webpage documentation at /swagger-ui.html
starter-webflux - could be replaced, just using WebClient
starter-test - test framework, mock libary, fake HTTP requests
h2 - a fake database
junit-platform-launcher - lets gradle run

**Run**
Start Docker Desktop, then from `notification-hub/`:

```powershell
docker compose up --build
```

Brings up Postgres + the app together. Secrets live in a gitignored `.env` next to
`docker-compose.yml` - no more exporting `$env:` vars by hand every session.

To iterate on the app from Gradle instead, run just the database:

```powershell
docker compose up -d postgres
.\gradlew.bat bootRun
```

1. Untrack postgres_data, fix the jar name, unify the docs — small cleanup, finishes the Docker work you already started.
2. Testcontainers. Still the highest-value next step. You test against H2 (build.gradle.kts:35) but ship Postgres, so the DO $$ ... $$ block and partial unique index in V5__idempotency_unique_key.sql
   are never exercised by CI. You now have compose proving the Postgres wiring works — Testcontainers makes CI prove it too.
3. CI builds and pushes the image. Trivial now that the Dockerfile exists.
4. Get the HTTP send out of the transaction. Unchanged and still the real architectural debt: NotificationService.process() does the webhook call inside @Transactional
   (NotificationService.java:30-38), and CanvasPoller calls controller.create() directly in-process (CanvasPoller.java:119). Add an outbox table first.
5. Kafka. Poller → notifications.requested → consumer sends → notifications.delivered, with retry/backoff and a DLT replacing the "catch, mark FAILED, move on" at
   NotificationRequestController.java:63. Also lets you delete the Thread.sleep(600) throttles.
6. Redis rate limiter, when you have >1 replica.
7. Kubernetes. Same three blockers as before: split health into liveness/readiness (currently just health,info at application.yml:21), move Flyway to an init container/Job so replicas don't race, and
   solve the @Scheduled singleton problem — CanvasPoller would poll Canvas once per replica, so you need ShedLock, leader election, or a CronJob.