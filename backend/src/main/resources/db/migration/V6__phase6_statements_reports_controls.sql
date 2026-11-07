-- Phase 6: ledger statements, credit-limit controls (overdue rule, exception expiry) and reports.

INSERT INTO system_settings (key, value, description) VALUES
    ('OVERDUE_INDENT_ACTION', 'ALLOW', 'Customer has dues older than their credit period: ALLOW, HOLD (admin must authorise) or BLOCK new indents'),
    ('OVERDUE_GRACE_DAYS',    '0',     'Extra days after the credit period before dues count as overdue');

INSERT INTO notification_settings (event_type, description, title_template, body_template) VALUES
    ('CREDIT_EXCEPTION_EXPIRED', 'Temporary credit limit expired',  'Extra credit of {amount} expired', 'Your temporary credit limit ended on {validUntil}'),
    ('ADJUSTMENT_POSTED',        'Adjustment posted to account',    '{adjustmentNo} posted',            '{type} of {amount}: {reason}'),
    ('INDENT_HELD_OVERDUE',      'Indent held for overdue dues',    'Indent {indentNo} held - overdue', '{customerName}: {reason}');

-- Ageing walks each customer's debits newest-first; statements read a date range per customer.
CREATE INDEX ix_ledger_customer_debits ON ledger_entries (customer_id, entry_at DESC, id DESC) WHERE debit > 0;
CREATE INDEX ix_ledger_customer_time ON ledger_entries (customer_id, entry_at, id);
CREATE INDEX ix_payments_verified_at ON payments (verified_at) WHERE status = 'VERIFIED';
CREATE INDEX ix_cashier_entries_recorded ON cashier_payment_entries (recorded_by, recorded_at);
