CREATE TABLE budget (
    id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(32) NOT NULL,
    preference_id VARCHAR(255),
    checkout_url VARCHAR(2048),
    payment_id VARCHAR(255) UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    budget_id UUID NOT NULL REFERENCES budget(id),
    event_type VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    os_updated BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL
);
