-- Turns notification_requests into a transactional outbox.
--
-- Before this, the HTTP send happened inline inside the request thread and inside
-- a @Transactional, so a slow webhook held a DB connection open and a failed send
-- was only visible as a status flip with no record of why. Now the row is the
-- queue: a background dispatcher claims due rows, sends outside any transaction,
-- and writes the outcome back.
--
-- status lifecycle: QUEUED -> SENDING -> SENT
--                                   \-> RETRY -> (SENDING -> ...) -> DEAD

ALTER TABLE notification_requests
  ADD COLUMN IF NOT EXISTS attempts        INTEGER     NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS last_error      TEXT,
  ADD COLUMN IF NOT EXISTS claimed_at      TIMESTAMPTZ;

-- Rows written before this migration never had a due time. Anything still sitting
-- in QUEUED should be picked up on the next dispatcher pass rather than stranded.
UPDATE notification_requests
   SET next_attempt_at = now()
 WHERE status = 'QUEUED'
   AND next_attempt_at IS NULL;

-- The dispatcher's claim query orders by next_attempt_at over just the due rows.
-- Partial index keeps it small: SENT rows are the overwhelming majority over time
-- and none of them are ever claimed.
CREATE INDEX IF NOT EXISTS ix_notification_requests_due
    ON notification_requests (next_attempt_at)
 WHERE status IN ('QUEUED', 'RETRY');

-- Used by the reaper that rescues rows stuck in SENDING after a crash.
CREATE INDEX IF NOT EXISTS ix_notification_requests_claimed
    ON notification_requests (claimed_at)
 WHERE status = 'SENDING';
