ALTER TABLE delivery_logs
  DROP COLUMN IF EXISTS device_id;

DROP TABLE IF EXISTS devices CASCADE;

ALTER TABLE delivery_logs
  RENAME COLUMN apns_response TO provider_response;

ALTER TABLE notification_requests
  DROP COLUMN IF EXISTS target_type,
  DROP COLUMN IF EXISTS target_ref;
