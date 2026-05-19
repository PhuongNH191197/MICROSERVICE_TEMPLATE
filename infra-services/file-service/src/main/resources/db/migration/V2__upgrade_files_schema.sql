ALTER TABLE files
    ADD COLUMN IF NOT EXISTS file_key      VARCHAR(500),
    ADD COLUMN IF NOT EXISTS status        VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    ADD COLUMN IF NOT EXISTS uploader_id   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS public_url    VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS expires_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS confirmed_at  TIMESTAMPTZ;

UPDATE files SET file_key = bucket || '/' || stored_name WHERE file_key IS NULL;
UPDATE files SET uploader_id = user_id WHERE uploader_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uidx_files_file_key ON files(file_key) WHERE file_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_files_status ON files(status);
CREATE INDEX IF NOT EXISTS idx_files_uploader_status ON files(uploader_id, status);
CREATE INDEX IF NOT EXISTS idx_files_expires_at ON files(expires_at) WHERE status = 'PENDING';
