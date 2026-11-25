-- Select the application's DB. Repeatable; existing data is preserved.
CREATE TABLE IF NOT EXISTS draft_edit_leases (
    draft_id VARCHAR(36) NOT NULL PRIMARY KEY,
    editor_id BIGINT NOT NULL,
    editor_name VARCHAR(100) NOT NULL,
    token VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revision BIGINT NULL
);
