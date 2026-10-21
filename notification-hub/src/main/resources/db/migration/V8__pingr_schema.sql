-- Pingr: users subscribe to feeds, polling a feed yields events, and new or changed
-- events become notifications in the existing outbox.
--
--   users 1--* feeds *--* events        (linked through feed_events)
--   users 1--* notification_requests    (who an alert is for; NULL for REST posts)

-- users has existed since V1 with nothing using it. external_id holds the Discord user
-- id. webhook_url is where this user's alerts go; NULL means the configured default
-- webhook, which is everyone for now.
ALTER TABLE users
  ADD COLUMN webhook_url TEXT;

-- One row per subscription. params is the source-specific query: a location and genre,
-- or a game title and price cap. Feeds with the same source_type and params are polled
-- with a single API call between them. jsonb equality ignores key order and whitespace,
-- so semantically equal params group together.
--
-- source_type is deliberately not CHECK-constrained: adding a source should be one new
-- class, not a migration.
CREATE TABLE feeds (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_type TEXT NOT NULL,
    params      JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Subscribing twice to the same thing is one subscription.
CREATE UNIQUE INDEX uq_feeds_user_query ON feeds (user_id, source_type, params);

-- Polling looks feeds up by query, not by user.
CREATE INDEX ix_feeds_query ON feeds (source_type, params);

-- One row per item a source has returned, shared by every feed that matched it.
-- content_hash covers the fields a subscriber would want to hear changed (price, dates,
-- venue); a different hash on a later poll is what triggers an update alert.
CREATE TABLE events (
    id            BIGSERIAL PRIMARY KEY,
    source        TEXT NOT NULL,
    external_id   TEXT NOT NULL,
    title         TEXT NOT NULL,
    starts_at     TIMESTAMPTZ,
    on_sale_at    TIMESTAMPTZ,
    venue         TEXT,
    price         NUMERIC(10, 2),
    url           TEXT,
    content_hash  TEXT NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_events_source_item UNIQUE (source, external_id)
);

-- Which feeds have matched which events. Reminders walk this from an event back to the
-- users who should hear about it.
CREATE TABLE feed_events (
    feed_id  BIGINT NOT NULL REFERENCES feeds(id)  ON DELETE CASCADE,
    event_id BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    PRIMARY KEY (feed_id, event_id)
);

CREATE INDEX ix_feed_events_event ON feed_events (event_id);

-- Who a notification is for. NULL for anything posted through the REST API.
ALTER TABLE notification_requests
  ADD COLUMN user_id BIGINT REFERENCES users(id) ON DELETE CASCADE;

-- Dedupe moves from (source item, channel) to (source item, channel, user): one concert
-- alerting two subscribers is two notifications, not a duplicate.
--
-- NULLS NOT DISTINCT (Postgres 15+) makes two NULL user_ids collide, so REST posts,
-- which have no user, still deduplicate exactly as they did under V5.
DROP INDEX IF EXISTS uq_notification_source;

CREATE UNIQUE INDEX uq_notification_source_user
    ON notification_requests (external_source, external_id, channel, user_id) NULLS NOT DISTINCT
 WHERE external_source IS NOT NULL
   AND external_id     IS NOT NULL
   AND channel         IS NOT NULL;
