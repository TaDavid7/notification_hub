# Notification Hub

A backend notification delivery system built with **Spring Boot**, **PostgreSQL** with flyway migrations
It routes notifications to multiple channels while carefully logging each request

---

## Setup
- Docker Desktop
- Git
- Java 17 (only if you want to run outside Docker)

### Clone
```bash
git clone https://github.com/TaDavid7/notification_hub.git
cd notification_hub/notification-hub
```

### Webhook URLs

#### Discord
For discord create a server, or one that you have permissions and create a webhook integration. You can do this by going into your channel settings and click on new webhook. You can specify what the name is and channel after clicking the webhook you made. Then copy the webhook URL.

#### Slack
For slack create a workspace, or one that you have permissions and create an app by clicking add apps on the left, and then browse apps. There should be able to click on Build, where you can create an app and then in the incoming WebHooks section create a new WebHook and copy the URL.

#### Configure app settings
Create a `.env` file next to `docker-compose.yml` and put your webhook URLs in it. This
file is gitignored, so your secrets never get committed:

```ini
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/...
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...

# Optional - only needed for the Canvas poller
CANVAS_BASE_URL=https://yourschool.instructure.com
CANVAS_TOKEN=...
CANVAS_COURSE_IDS=12345,67890
```

### Run

```bash
docker compose up --build
```

This starts PostgreSQL and the app together. Compose waits for the database to pass its
health check before booting the app, and Flyway applies the migrations on startup.

- API: http://localhost:8080/api/notifications
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- Postgres (from the host): `localhost:5442`

### How delivery works

Posting a notification does not send it. `POST /api/notifications` writes a row and
returns **202 Accepted**; a background dispatcher picks it up within a few seconds,
calls the Discord or Slack webhook, and writes the result back. This keeps a slow or
failing webhook off the request thread and out of the database transaction.

Poll `GET /api/notifications/{id}` for the outcome:

| status | meaning |
| --- | --- |
| `QUEUED` | accepted, not yet attempted |
| `SENDING` | a dispatcher is sending it right now |
| `SENT` | the provider accepted it |
| `RETRY` | last attempt failed; `nextAttemptAt` says when the next one is due |
| `DEAD` | gave up after 5 attempts; `lastError` says why |

Failed sends retry with exponential backoff (30s, doubling, capped at an hour, with
jitter). Re-posting the same `externalSource` + `externalId` + `channel` returns
**200** with the existing row instead of creating a duplicate. Every attempt leaves a
row in `delivery_logs`. Timings are configurable under `notifications.*` in
`application.yml`.

To stop, `Ctrl+C` then `docker compose down`. Add `-v` to also wipe the database volume.

#### Running the app outside Docker

If you'd rather run the app from Gradle, start just the database and point the app at it:

```bash
docker compose up -d postgres
./gradlew bootRun          # .\gradlew.bat bootRun on Windows
```

The defaults in `application.yml` already target `localhost:5442`, so no extra config is
needed. Webhook URLs come from environment variables in this mode, not the `.env` file.

### Tests

```bash
./gradlew test
```

Integration tests run against a real PostgreSQL container via Testcontainers, so
**Docker must be running** — the same `postgres:15` image compose uses. Migrations are
applied by Flyway and the schema is validated against the JPA entities, so a passing
suite means the migrations and the entities actually agree.

---
## Future improvements
- JWT security
- Add an event driven notification (automatic)
- Add customization for kind of message for event pipeline

---

## Author
Made by David T.

---

## License 
Licensed under the Apache License 2.0 – see the [LICENSE](LICENSE) file for details.

