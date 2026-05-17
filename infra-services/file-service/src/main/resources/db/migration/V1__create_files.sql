CREATE TABLE files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(255),
    original_name VARCHAR(500),
    stored_name VARCHAR(500) NOT NULL,
    bucket VARCHAR(100) NOT NULL,
    size BIGINT,
    content_type VARCHAR(100),
    public_file BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_files_user_id ON files(user_id);
