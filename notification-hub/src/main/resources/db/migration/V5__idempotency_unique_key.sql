-- One notification per (source item) per channel
ALTER TABLE notification_requests
  ADD COLUMN IF NOT EXISTS external_source TEXT,
  ADD COLUMN IF NOT EXISTS external_id     TEXT;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_indexes WHERE indexname = 'uq_notification_source'
  ) THEN
    CREATE UNIQUE INDEX uq_notification_source
      ON notification_requests (external_source, external_id, channel)
      WHERE external_source IS NOT NULL
        AND external_id     IS NOT NULL
        AND channel         IS NOT NULL;
  END IF;
END $$;
