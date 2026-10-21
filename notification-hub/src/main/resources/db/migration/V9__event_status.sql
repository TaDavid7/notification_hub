-- A cancellation or postponement is often visible only in an event's status: the date,
-- venue and price can all stay as they were. Status is part of the content hash, so it
-- is kept alongside it. Values are EventStatus names.
ALTER TABLE events
  ADD COLUMN status TEXT NOT NULL DEFAULT 'SCHEDULED';
