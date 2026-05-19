CREATE TABLE IF NOT EXISTS profiles (
    id VARCHAR(255) PRIMARY KEY,
    full_name VARCHAR(255),
    avatar_url VARCHAR(1000),
    bio VARCHAR(1000),
    phone VARCHAR(50),
    address VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
