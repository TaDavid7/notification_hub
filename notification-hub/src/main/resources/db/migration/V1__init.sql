CREATE TABLE users(
    id BIGSERIAL PRIMARY KEY,
    external_id TEXT UNIQUE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE devices(
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE, --links device to user
    platform TEXT NOT NULL, -- device type
    apns_token TEXT UNIQUE NOT NULL, --unique token for each device
    bundle_id TEXT NOT NULL, --tells where the device registered
    is_sandbox BOOLEAN NOT NULL DEFAULT true, --apples sandbox test push environment
    last_seen_at TIMESTAMPTZ -- tracks when device was last active
);