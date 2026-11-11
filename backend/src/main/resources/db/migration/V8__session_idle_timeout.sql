-- Idle sign-out: a session ends after app.security.idle-minutes without an API call.
ALTER TABLE user_sessions ADD COLUMN last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now();
