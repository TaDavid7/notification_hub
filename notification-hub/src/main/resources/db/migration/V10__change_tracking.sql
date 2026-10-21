-- Counts an event's content changes. Each update alert is keyed by it in the outbox, so
-- every change gets a key of its own: keyed by content hash instead, a price going
-- 49 -> 39 -> 49 -> 39 would find its second "39" already used and stay silent.
ALTER TABLE events
  ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;

-- The reminder job scans for events starting, or going on sale, within the next window.
-- Partial, because deals and TBA events have neither time and are never scanned for.
CREATE INDEX ix_events_starts_at  ON events (starts_at)  WHERE starts_at  IS NOT NULL;
CREATE INDEX ix_events_on_sale_at ON events (on_sale_at) WHERE on_sale_at IS NOT NULL;
