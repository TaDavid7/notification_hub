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

   Secrets now come from SSM rather than plaintext env vars - see the SSM table below.

   Budget roughly $10-25/month; App Runner bills for idle memory, so pause it when not
   demoing.

   **Current state (19 Aug 2026): everything is stopped again.** App Runner PAUSED, RDS
   stopped, after bringing both up to apply the SSM switch-over and verify the outbox in
   production. Nothing is billed except RDS storage (~$2/month for 20GB).

   > **RDS auto-starts after 7 days - so around 26 Aug 2026.** AWS will not leave an
   > instance stopped indefinitely. Either stop it again, or snapshot and delete it if
   > you're done for a while.

   Note that App Runner refuses `update-service` while PAUSED
   (`InvalidStateException`), so any config change means resuming first.

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
  several instances - so the Redis/ShedLock item above only needs to cover `CanvasPoller`,
  not this loop.

  **Verified in production**, not just in tests: `POST` returned 202/QUEUED, and the row
  reached SENT on attempt 1 using the webhook read from SSM. Before that, the same run
  locally against real Discord and Slack returned `Discord OK 200` and `SLACK_DELIVERED`,
  with `V6` applying as an upgrade over an existing V1-V5 schema rather than a fresh
  create.

- ~~**Secrets in SSM.**~~ App Runner reads all three secrets from encrypted Parameter
  Store; only `DB_URL` and `DB_USERNAME` remain plaintext, and neither is a secret.

  | resource | name |
  | --- | --- |
  | SSM (SecureString) | `/notification-hub/discord-webhook-url` |
  | SSM (SecureString) | `/notification-hub/slack-webhook-url` |
  | SSM (SecureString) | `/notification-hub/db-password` |
  | IAM role | `AppRunnerInstanceRole` + inline `ReadNotificationHubSecrets` |

  Both webhook URLs were rotated at the same time, since the originals had been read out
  of the App Runner config in plaintext. To rotate again: regenerate in Discord/Slack,
  then `aws ssm put-parameter --overwrite`; the service needs no change, but it does need
  a restart to pick up the new value.

  If you ever rebuild the service config by hand, pass `Cpu`/`Memory` explicitly -
  omitting `InstanceConfiguration` fields resets them to App Runner defaults.

**Deferred (not dropped)**

- **Test the HTTP senders.** `DiscordSender` is the least-covered class in the repo (9%)
  and contains the retry/`Retry-After` parsing, which is exactly the logic worth having
  tests around. `SlackSender` is at 20%. Needs a stubbed HTTP server (WireMock or OkHttp's
  `MockWebServer`) so a test can play Discord returning 429 or 500. This is the one thing
  standing between the coverage gate and a number above `0.50` - do it before raising the
  gate, not after.

- **Legacy `FAILED` rows.** Production still holds at least one row with status `FAILED`
  from the pre-outbox design (`id=1`, created 18 Aug). `FAILED` is not part of the new
  lifecycle and the dispatcher only claims `QUEUED`/`RETRY`, so it will sit there forever.
  Harmless as history. If you'd rather they got one attempt under the new system, that's a
  one-line `V7`:
  `UPDATE notification_requests SET status='RETRY', next_attempt_at=now() WHERE status='FAILED';`

- **Canvas poller has no tests.** It sits at 24% and is the only component never exercised
  end to end - the local and production verification both went through the REST API, not a
  real Canvas poll. `fetchAllPages` pagination and `parseNext`'s Link-header regex are pure
  functions and easy to test; the polling itself needs a stubbed Canvas.

- **Rollback is manual.** Images are tagged with the short SHA, so rolling back is
  possible, but it means editing the App Runner image tag by hand. Worth a documented
  one-liner if this ever matters.

**Not started (and fine)**

- **NAT Gateway.** See the known tradeoff above - RDS stays publicly reachable until this
  holds data worth protecting.
- **JWT security**, event-driven notifications, and per-channel message formatting, all
  from the README's future-improvements list. Nothing above blocks them.
