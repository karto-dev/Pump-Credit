-- Phase 7: SMS / WhatsApp outbox worker, reminders, backups.

INSERT INTO notification_settings (event_type, description, title_template, body_template) VALUES
    ('BACKUP_FAILED', 'Database backup failed', 'Backup failed', '{error}');

-- Customers get payment-related messages on SMS by default once a provider is configured;
-- with the default LOG provider these only appear in the server log and the delivery list.
UPDATE notification_settings SET sms_enabled = TRUE
 WHERE event_type IN ('FUEL_SUPPLIED', 'PAYMENT_VERIFIED', 'PAYMENT_REJECTED', 'OUTSTANDING_REMINDER');

CREATE INDEX ix_backup_runs_started ON backup_runs (started_at DESC);
CREATE INDEX ix_notification_deliveries_created ON notification_deliveries (created_at DESC);
CREATE INDEX ix_notifications_reminders ON notifications (user_id, created_at DESC) WHERE event_type = 'OUTSTANDING_REMINDER';
