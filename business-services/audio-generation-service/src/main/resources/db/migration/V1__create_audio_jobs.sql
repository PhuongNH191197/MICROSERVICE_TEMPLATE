CREATE TABLE audio_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(255) NOT NULL,
    job_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    genre VARCHAR(50),
    mood VARCHAR(50),
    instrument VARCHAR(50),
    mode VARCHAR(20) DEFAULT 'CLIP',
    source_file_key VARCHAR(500),
    segment_start_ms INTEGER,
    segment_end_ms INTEGER,
    tts_text VARCHAR(100),
    voice_id VARCHAR(100),
    format VARCHAR(10) NOT NULL DEFAULT 'MP3',
    prompt TEXT,
    title VARCHAR(200),
    file_id UUID,
    audio_url TEXT,
    preview_versions TEXT,
    cache_key VARCHAR(255),
    duration_seconds INTEGER,
    file_size_bytes BIGINT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);
CREATE INDEX idx_audio_jobs_user_id ON audio_jobs(user_id);
CREATE INDEX idx_audio_jobs_status ON audio_jobs(status);
CREATE INDEX idx_audio_jobs_created ON audio_jobs(created_at DESC);
