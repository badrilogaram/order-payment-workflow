CREATE TABLE IF NOT EXISTS simulated_payment (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    payment_id VARCHAR(36) NOT NULL UNIQUE,
    order_id VARCHAR(36) NOT NULL UNIQUE,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'USD'),
    scenario VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'CAPTURED', 'DECLINED')),
    request_attempts INTEGER NOT NULL CHECK (request_attempts > 0),
    capture_count INTEGER NOT NULL CHECK (capture_count IN (0, 1)),
    CHECK ((status = 'CAPTURED' AND capture_count = 1) OR (status <> 'CAPTURED' AND capture_count = 0))
);
