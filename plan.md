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
testcontainers (junit-jupiter + postgresql) - spins up a real postgres:15 in tests, replaced h2
junit-platform-launcher - lets gradle run

**Run (local)**
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

---

**Plan**

Kafka and Kubernetes are dropped. AWS first, Redis second.

1. ~~**Deploy to AWS**~~ - **DONE.** Live at https://pukt4aem9j.us-east-1.awsapprunner.com

   App Runner + RDS Postgres + ECR, account 906048429586, us-east-1. No app code changed -
   everything was already read from environment variables.

   | resource | name |
   | --- | --- |
   | App Runner service | `notification-hub` |
   | RDS Postgres 15.17 | `notification-hub-db` (db.t4g.micro) |
   | ECR repo | `906048429586.dkr.ecr.us-east-1.amazonaws.com/notification-hub` |
   | IAM role (ECR pull) | `AppRunnerECRAccessRole` |

   Pushing `:latest` to ECR auto-redeploys the service.

   **Known tradeoff:** RDS is publicly accessible with the security group open on 5432,
   protected only by a 28-character random password. App Runner's VPC connector routes
   *all* egress through the VPC, which would have blocked the Discord/Slack webhooks
   without a NAT Gateway (~$32/month, more than everything else combined). Fix later with
   a NAT Gateway if this ever holds real data.

   **Still deferred from this step:** webhook secrets live as plain App Runner env vars
   rather than SSM. About 20 minutes.

   Budget roughly $10-25/month; App Runner bills for idle memory, so pause it when not
   demoing.

   **Current state: everything is stopped.** App Runner is PAUSED, RDS is STOPPED.
   Nothing is being billed except RDS storage (~$2/month for 20GB).

   > **RDS auto-starts after 7 days.** AWS will not leave an instance stopped
   > indefinitely. Either stop it again, or snapshot and delete it if you're done for a
   > while.

   **To bring it back up** (RDS first - App Runner health-checks the database):

   ```powershell
   $aws = "C:\Program Files\Amazon\AWSCLIV2\aws.exe"
   $arn = "arn:aws:apprunner:us-east-1:906048429586:service/notification-hub/0a14f321bf4040e691c21f91716637e2"

   & $aws rds start-db-instance --db-instance-identifier notification-hub-db --region us-east-1
   # wait for available (a few minutes)
   & $aws rds wait db-instance-available --db-instance-identifier notification-hub-db --region us-east-1
   & $aws apprunner resume-service --service-arn $arn --region us-east-1
   ```

   **To ship a code change:** merge to `main`. The `push-image` job in
   `.github/workflows/ci.yml` runs after the test/coverage job, builds the image, and
   pushes `:latest` (plus a short-SHA tag for rollbacks) to ECR. App Runner auto-redeploys
   *if the service is running* - it's paused by default, so a push while paused just
   updates the image and deploys whenever you resume.

   Auth is GitHub OIDC, no stored access keys:

   | resource | name |
   | --- | --- |
   | OIDC provider | `token.actions.githubusercontent.com` |
   | IAM role | `GitHubActionsECRPushRole` |
   | inline policy | `ECRPushNotificationHub` (scoped to the one ECR repo) |

   The role's trust policy only accepts `repo:TaDavid7/notification_hub:ref:refs/heads/main`.
   Pushes from a branch or a fork PR cannot assume it - that's deliberate, but it means
   renaming the default branch or moving the repo breaks the push job until the trust
   policy is updated.

   **Manual push** (fallback if Actions is down):

   ```powershell
   $repo = "906048429586.dkr.ecr.us-east-1.amazonaws.com/notification-hub"
   $token = & $aws ecr get-login-password --region us-east-1
   docker login --username AWS --password $token 906048429586.dkr.ecr.us-east-1.amazonaws.com
   cd notification-hub
   docker build -t notification-hub:latest .
   docker tag notification-hub:latest "${repo}:latest"
   docker push "${repo}:latest"    # App Runner auto-redeploys
   ```

   Gotchas hit during setup, so they don't cost time twice:
   - PowerShell 5.1 mangles `docker login --password-stdin`; pass `--password $token`.
   - `curl.exe` on Windows mangles inline JSON; write it to a file and use
     `--data-binary "@file.json"`.
   - The DB master password is in the session scratchpad, not the repo. If it's lost,
     reset it with `aws rds modify-db-instance --master-user-password`.

2. **Redis (ElastiCache), once max instances goes above 1.** Two jobs: ShedLock around
   `CanvasPoller.tick()` so only one instance polls - this is what unblocks scaling - and
   a shared token bucket so instances don't each get a full rate-limit budget against
   Discord and Slack. Worthless at one instance, so don't provision it early.
   Also needed before scaling: move Flyway out of app startup into a one-off task.

**Deferred (not dropped)**

- **Repo cleanup.** Coverage gate is `0.05` with `// 90%` beside it (build.gradle.kts:66,
  73). `System.out.println` throughout CanvasPoller - worth swapping to SLF4J while
  wiring up CI, since those lines are about to go to CloudWatch.

- **HTTP error handling and the scheduler block.** Best interview material here, and it
  needs no new infrastructure: the webhook call runs inside `@Transactional`
  (NotificationService.java:30-38), holding a DB connection across a 10s HTTP timeout;
  the controller catches `Exception`, logs nothing, and returns 201 for a failed send
  (NotificationRequestController.java:61-66); `fetchAllPages` calls `.block()` with no
  timeout, so one Canvas 401 kills the whole day's poll (CanvasPoller.java:184). Fix is a
  transactional outbox plus a worker with retry/backoff. Good candidate for right after
  the deploy is live.
