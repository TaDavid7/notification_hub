-- Table to record each notification request
CREATE TABLE notification_requests (
    id BIGSERIAL PRIMARY KEY,
    target_type TEXT NOT NULL,              -- "USER" or "TOKEN"
    target_ref TEXT NOT NULL,               -- user id (as string) or raw token
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    priority TEXT NOT NULL DEFAULT 'NORMAL',-- "NORMAL" or "HIGH"
    status TEXT NOT NULL DEFAULT 'QUEUED',  -- QUEUED | SENT | FAILED
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Table to log delivery attempts per device
CREATE TABLE delivery_logs (
    id BIGSERIAL PRIMARY KEY,
    request_id BIGINT NOT NULL REFERENCES notification_requests(id) ON DELETE CASCADE,
    device_id BIGINT REFERENCES devices(id),
    attempt INT NOT NULL DEFAULT 1,
    succeeded BOOLEAN NOT NULL,
    apns_response TEXT,
    error_msg TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
