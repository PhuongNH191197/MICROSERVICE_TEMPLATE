CREATE TABLE user_credits (
    user_id VARCHAR(255) PRIMARY KEY,
    balance INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE credit_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(255) NOT NULL,
    amount INTEGER NOT NULL,
    type VARCHAR(50) NOT NULL,
    job_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_credit_tx_user ON credit_transactions(user_id, created_at DESC);
