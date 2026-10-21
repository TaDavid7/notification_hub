# Pingr

A Discord bot that tells you about concerts near you and game deals under your price.
Subscribe with a slash command; Pingr polls Ticketmaster and CheapShark, notices what's
new or changed, and mentions you in Discord.

```
/subscribe concerts zip:94110 radius:25 genre:Rock
/watch game:Hades under:20
/list
/unsubscribe id:12
```

You hear about:

- **new** shows or deals matching a subscription. The first poll after subscribing sends
  one summary of what's already out there, rather than an alert per item;
- **changes** to ones you follow: a price drop, a new date or venue, a cancellation;
- shows **starting soon** (within a day) and tickets going **on sale soon** (within an hour).

Built with **Spring Boot**, **PostgreSQL** and Flyway migrations.

---

## Setup
- Docker Desktop
- Git
- Java 17 (only if you want to run outside Docker)

### Clone
```bash
git clone https://github.com/TaDavid7/notification_hub.git
cd notification_hub/pingr
```

### Webhook URLs

#### Discord
Alerts are posted through a Discord webhook. In a server you have permissions on, open the
channel's settings -> Integrations -> Webhooks -> New Webhook, choose the channel, and copy
the webhook URL. Naming the webhook "Pingr" is what members will see as the sender.

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

- Discord interactions: http://localhost:8080/discord/interactions (behind your tunnel)
- API: http://localhost:8080/api/notifications
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- Postgres (from the host): `localhost:5442`

To stop, `Ctrl+C` then `docker compose down`. Add `-v` to also wipe the database volume.

#### Running the app outside Docker

If you'd rather run the app from Gradle, start just the database and point the app at it:

```bash
docker compose up -d postgres
./gradlew bootRun          # .\gradlew.bat bootRun on Windows
```

The defaults in `application.yml` already target `localhost:5442`, so no extra config is
needed. Webhook URLs come from environment variables in this mode, not the `.env` file.

---

## How it works

```
/subscribe ─► feeds ─► poller ─► source (Ticketmaster, CheapShark) ─► change detection ─► outbox ─► Discord
                         ▲                                                  │
                    poll_groups                                  events, feed_events
```

- **Feeds and groups.** Each subscription is a feed: a source and its query params.
  Feeds asking the same thing form one poll group, so ten people watching the same area
  cost one API call.
- **Polling.** Every minute the poller claims due groups (`FOR UPDATE SKIP LOCKED`, so it
  is safe on several instances), fetches each once, and reschedules it for an hour later.
  A failing group backs off exponentially and respects the API's `Retry-After`. Requests
  are held to each API's rate limits (`polling.rateLimits` in `application.yml`).
- **Sources** only fetch and normalise. Adding one is a `SourceType` value and one
  `@Component` implementing `Source`.
- **Change detection** keeps the last state of every item with a content hash over its
  dates, venue, price and status. A new item, or a different hash, becomes an alert in the
  same transaction that records it, so an alert is never lost and never sent twice.
- **Reminders** come from a job that scans for events starting, or going on sale, soon.

### How delivery works

Nothing is sent inline. Every alert - and every `POST /api/notifications` - writes a row
to the outbox; a background dispatcher picks it up within a few seconds, calls the
Discord or Slack webhook, and writes the result back. This keeps a slow or failing
webhook off the request thread and out of the database transaction.
`POST /api/notifications` therefore returns **202 Accepted**; poll
`GET /api/notifications/{id}` for the outcome:

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

### Tests

```bash
./gradlew test
```

Integration tests run against a real PostgreSQL container via Testcontainers, so
**Docker must be running** — the same `postgres:15` image compose uses. Migrations are
applied by Flyway and the schema is validated against the JPA entities, so a passing
suite means the migrations and the entities actually agree. The external APIs and
Discord are played by stubs; no test needs a key or the network.

---
## Future improvements
- Alert when a deal that ended comes back
- Per-user webhooks (`users.webhook_url` is in place; the dispatcher doesn't read it yet)
- A rate limit shared between instances (Redis), for running more than one
- JWT security for the REST API

---

## Author
Made by David T.

---

## License 
Licensed under the Apache License 2.0 – see the [LICENSE](LICENSE) file for details.
