-- Phase 2: indents and cashier credit sales

INSERT INTO system_settings (key, value, description) VALUES
    ('DUPLICATE_SALE_WINDOW_MIN', '15', 'A sale with the same customer, vehicle and amount inside this window needs confirmation');

INSERT INTO notification_settings (event_type, description, title_template, body_template) VALUES
    ('INDENT_REJECTED',   'Indent rejected or cancelled',     'Indent {indentNo} {action}',      '{reason}'),
    ('INDENT_ON_HOLD',    'Indent held for credit limit',     'Indent {indentNo} on hold',       'Your indent is waiting for admin approval: {reason}'),
    ('INDENT_AUTHORISED', 'Held indent authorised by admin',  'Indent {indentNo} approved',      'Admin authorised your indent for {vehicleNo}');

-- Lookups the cashier queue and sales register hit constantly.
CREATE INDEX ix_indents_vehicle ON indents (vehicle_id, created_at DESC);
CREATE INDEX ix_bill_attachments_sha ON bill_attachments (sha256);
