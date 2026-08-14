CREATE TABLE users (
    id      UUID PRIMARY KEY,
    email   VARCHAR(320) NOT NULL UNIQUE,
    active  BOOLEAN NOT NULL,

    -- Campaign requests normalize recipient emails to lowercase before the user
    -- lookup; the lookup itself is a case-sensitive equality match. Enforce the
    -- same invariant on the storage side so an active user can never become
    -- unreachable because a seed/import stored their address with uppercase letters.
    CONSTRAINT chk_users_email_lowercase CHECK (email = lower(email))
);

CREATE TABLE campaign (
    id          UUID PRIMARY KEY,
    subject     VARCHAR(255) NOT NULL,
    message     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE campaign_recipient (
    id               UUID PRIMARY KEY,
    campaign_id      UUID NOT NULL REFERENCES campaign (id),
    user_id          UUID NOT NULL REFERENCES users (id),
    email            VARCHAR(320) NOT NULL,
    status           VARCHAR(16) NOT NULL, -- PENDING | SENDING | SENT | FAILED
    attempts         INT NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    last_error       VARCHAR(1000),
    updated_at       TIMESTAMPTZ NOT NULL,

    -- deduplication / idempotency: a user can appear in a campaign only once
    CONSTRAINT uq_campaign_recipient_user UNIQUE (campaign_id, user_id)
);

-- status API aggregation (GROUP BY status per campaign)
CREATE INDEX idx_recipient_campaign_status ON campaign_recipient (campaign_id, status);

-- work selection for the worker / recovery poller
CREATE INDEX idx_recipient_status_next_attempt ON campaign_recipient (status, next_attempt_at);
