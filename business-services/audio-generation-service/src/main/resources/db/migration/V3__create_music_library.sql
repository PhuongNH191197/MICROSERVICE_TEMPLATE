CREATE TABLE music_library (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(255) NOT NULL,
    artist VARCHAR(255),
    genre VARCHAR(100),
    duration_ms INTEGER,
    file_key VARCHAR(500) NOT NULL,
    bucket VARCHAR(100) NOT NULL DEFAULT 'media-audio',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_music_library_active ON music_library(active, created_at DESC);
