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

#### Discord slash commands
Subscriptions are made with slash commands (`/subscribe concerts`, `/watch`, `/list`,
`/unsubscribe`), which need a Discord application:

1. At https://discord.com/developers/applications, create an application. From its
   **General Information** page copy the **Application ID** and **Public Key**; from
   **Bot**, reset and copy the **Token**.
2. Invite it to your server: **OAuth2 -> URL Generator**, tick `applications.commands`,
   open the generated URL.
3. Set **Interactions Endpoint URL** to `https://<your host>/discord/interactions`. Discord
   only calls public HTTPS URLs, so locally you need a tunnel such as `cloudflared` or
   `ngrok`. Discord tests the URL when you save it; the app must be running.

The commands are registered each time the app starts. With `DISCORD_GUILD_ID` set they go
to that one server and appear immediately; without it they are global and can take up to
an hour to appear.

#### Configure app settings
Create a `.env` file next to `docker-compose.yml` and put your webhook URLs in it. This
file is gitignored, so your secrets never get committed:

```ini
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/...
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...

# Slash commands - see above
DISCORD_APPLICATION_ID=...
DISCORD_PUBLIC_KEY=...
DISCORD_BOT_TOKEN=...
# Optional - your server's id (right-click it with Developer Mode on -> Copy Server ID)
DISCORD_GUILD_ID=...

# Optional - enables concert and event alerts. Create an app at
# developer.ticketmaster.com and use its Consumer Key. Game deals need no key.
TICKETMASTER_API_KEY=...
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

