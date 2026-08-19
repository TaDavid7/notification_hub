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

**Done since the deploy**

- ~~**Repo cleanup.**~~ Coverage gate now reads `0.50`, which is a real ratchet just under
  actual line coverage rather than `0.05` wearing a `// 90%` comment. CanvasPoller logs
  through SLF4J instead of `System.out.println`, so the lines arrive in CloudWatch with a
  level and a timestamp - and failures now carry a stack trace, which the printlns threw
  away.

  Coverage went 29.5% -> 53.5% with the outbox tests below. The remaining gap is almost
  entirely the two HTTP senders (DiscordSender 9%, SlackSender 20%); testing those needs a
  stubbed HTTP server (WireMock or `MockWebServer`), which is the next honest step before
  the gate can move up.

- ~~**HTTP error handling and the scheduler block.**~~ Replaced with a transactional
  outbox (`V6__outbox.sql`).

  | was | now |
  | --- | --- |
  | webhook sent inside `@Transactional` | send happens with no transaction open |
  | 201 returned for a failed send | 202 Accepted; status is polled from the row |
  | `catch (Exception)` logging nothing | failures logged, retried, and kept in `last_error` |
  | `.block()` with no timeout | `.block(Duration.ofSeconds(20))` |
  | one failing Canvas call killed the poll | each endpoint fetched independently |
  | poller called the controller directly | both go through `NotificationIntakeService` |

  Shape: `POST` writes a QUEUED row and returns. `NotificationDispatcher` claims a batch
  every 5s (`FOR UPDATE SKIP LOCKED`), sends outside any transaction, and writes each
  outcome back in its own short transaction. Failures retry with jittered exponential
  backoff (30s doubling, capped at 1h) and go DEAD after 5 attempts with the error kept.
  A reaper requeues rows left in SENDING by a crashed instance.

  **API change worth knowing:** `POST /api/notifications` returns **202**, not 201. It
  cannot honestly report delivery any more, because delivery has not happened yet. A
  duplicate still returns 200 with the existing row. Tuning lives under `notifications.*`
  in `application.yml`.

  Because the claim query uses `SKIP LOCKED`, the dispatcher is already safe to run on
  several instances - so the Redis/ShedLock item below only needs to cover `CanvasPoller`,
  not this loop.

**Deferred (not dropped)**

- **Finish the SSM switch-over.** The parameters and the IAM role exist; App Runner is
  still reading plaintext env vars because `update-service` refuses to run against a
  PAUSED service:

  | resource | name |
  | --- | --- |
  | SSM (SecureString) | `/notification-hub/discord-webhook-url` |
  | SSM (SecureString) | `/notification-hub/slack-webhook-url` |
  | SSM (SecureString) | `/notification-hub/db-password` |
  | IAM role | `AppRunnerInstanceRole` + inline `ReadNotificationHubSecrets` |

  To finish, resume the service first (RDS, then App Runner - see above), then move the
  three values from `RuntimeEnvironmentVariables` to `RuntimeEnvironmentSecrets` and
  attach the instance role. `DB_URL` and `DB_USERNAME` stay as plain env vars.

  ```powershell
  # DB_URL is read back from the service so the endpoint isn't written down here
  $arn = "arn:aws:apprunner:us-east-1:906048429586:service/notification-hub/0a14f321bf4040e691c21f91716637e2"
  $dbUrl = (& $aws apprunner describe-service --service-arn $arn --region us-east-1 `
      --query 'Service.SourceConfiguration.ImageRepository.ImageConfiguration.RuntimeEnvironmentVariables.DB_URL' `
      --output text)

  $p = "arn:aws:ssm:us-east-1:906048429586:parameter/notification-hub"
  $cfg = @{
    ServiceArn = $arn
    SourceConfiguration = @{
      ImageRepository = @{
        ImageIdentifier = "906048429586.dkr.ecr.us-east-1.amazonaws.com/notification-hub:latest"
        ImageRepositoryType = "ECR"
        ImageConfiguration = @{
          Port = "8080"
          RuntimeEnvironmentVariables = @{ DB_URL = $dbUrl; DB_USERNAME = "postgres" }
          RuntimeEnvironmentSecrets = @{
            DB_PASSWORD         = "$p/db-password"
            DISCORD_WEBHOOK_URL = "$p/discord-webhook-url"
            SLACK_WEBHOOK_URL   = "$p/slack-webhook-url"
          }
        }
      }
      AutoDeploymentsEnabled = $true
      AuthenticationConfiguration = @{ AccessRoleArn = "arn:aws:iam::906048429586:role/AppRunnerECRAccessRole" }
    }
    InstanceConfiguration = @{
      Cpu = "1024"; Memory = "2048"
      InstanceRoleArn = "arn:aws:iam::906048429586:role/AppRunnerInstanceRole"
    }
  }
  $cfg | ConvertTo-Json -Depth 10 | Out-File -Encoding utf8 update.json
  & $aws apprunner update-service --region us-east-1 --cli-input-json file://update.json
  ```

  Passing `Cpu`/`Memory` explicitly is not optional - omitting `InstanceConfiguration`
  fields resets them to the App Runner defaults.

  **Rotate the webhook URLs when convenient.** Both were read out of the App Runner config
  in plaintext while setting this up, so they've been on a terminal. Regenerate them in
  Discord and Slack, then `aws ssm put-parameter --overwrite`; nothing else changes once
  the service reads from SSM.

- **Test the HTTP senders.** DiscordSender is the least-covered class in the repo (9%) and
  contains the retry/`Retry-After` parsing, which is exactly the logic worth having tests
  around. Needs a stubbed HTTP server. Raise the coverage gate afterwards.
