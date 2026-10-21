-- One row per distinct query: feeds with the same source_type and params are polled
-- with a single API call between them. Rows are kept in step with feeds by the poller
-- itself (added for new queries, dropped when the last feed goes), so nothing else has
-- to remember to maintain them.
--
-- next_poll_at is both the schedule and the lease. Claiming a group pushes it forward
-- by the lease, so other instances skip it; if the claiming instance dies mid-poll, the
-- group simply comes due again when the lease runs out.
CREATE TABLE poll_groups (
    id             BIGSERIAL PRIMARY KEY,
    source_type    TEXT NOT NULL,
    params         JSONB NOT NULL,
    next_poll_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    failures       INTEGER NOT NULL DEFAULT 0,      -- in a row; drives the backoff
    last_polled_at TIMESTAMPTZ,
    last_error     TEXT,
    CONSTRAINT uq_poll_groups_query UNIQUE (source_type, params)
);

CREATE INDEX ix_poll_groups_due ON poll_groups (next_poll_at);

-- Set once a feed's first poll is done. Until then, what the source returns is what
-- already existed when the user subscribed, and gets one summary instead of an alert
-- per item.
ALTER TABLE feeds
  ADD COLUMN baselined_at TIMESTAMPTZ;
