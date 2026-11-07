-- Roles
INSERT INTO roles (code, name, description) VALUES
    ('CUSTOMER', 'Customer', 'Credit customer - sees only their own account'),
    ('CASHIER',  'Cashier',  'Processes indents, enters credit sales and records payments'),
    ('MANAGER',  'Manager',  'Monitors operations, verifies payments, resolves disputes, runs reports'),
    ('ADMIN',    'Admin',    'Full control: users, permissions, credit limits, settings');

-- Permissions (admin can re-map these to roles or override per user)
INSERT INTO permissions (code, module, description) VALUES
    ('DASHBOARD_VIEW',          'DASHBOARD', 'View own role dashboard'),
    ('INDENT_CREATE',           'INDENT',    'Raise fuel indents'),
    ('INDENT_VIEW_ALL',         'INDENT',    'View indents of all customers'),
    ('INDENT_PROCESS',          'INDENT',    'Accept / process / complete indents'),
    ('CREDIT_TXN_CREATE',       'SALES',     'Enter credit sales with bill photo'),
    ('CREDIT_TXN_VIEW_ALL',     'SALES',     'View credit sales of all customers'),
    ('CREDIT_TXN_EDIT',         'SALES',     'Edit credit sales that are not yet customer-approved'),
    ('BILL_APPROVE_OWN',        'SALES',     'Approve / reject own bills (customer)'),
    ('DISPUTE_RESOLVE',         'SALES',     'Investigate and resolve disputed bills'),
    ('PAYMENT_SUBMIT',          'PAYMENT',   'Submit payment claims (customer)'),
    ('PAYMENT_RECORD',          'PAYMENT',   'Record payments received (cashier)'),
    ('PAYMENT_VIEW_ALL',        'PAYMENT',   'View payments of all customers'),
    ('PAYMENT_VERIFY',          'PAYMENT',   'Verify / reject payments'),
    ('LEDGER_VIEW_OWN',         'LEDGER',    'View own ledger and statements'),
    ('LEDGER_VIEW_ALL',         'LEDGER',    'View ledger of any customer'),
    ('ADJUSTMENT_CREATE',       'LEDGER',    'Raise debit / credit adjustments'),
    ('ADJUSTMENT_APPROVE',      'LEDGER',    'Approve adjustments raised by others'),
    ('REPORT_VIEW',             'REPORTS',   'View operational reports'),
    ('REPORT_FINANCIAL',        'REPORTS',   'View financial reports (outstanding, ageing, recovery)'),
    ('REPORT_EXPORT',           'REPORTS',   'Export reports to Excel / PDF'),
    ('CUSTOMER_MANAGE',         'MASTERS',   'Create / edit customers'),
    ('VEHICLE_MANAGE',          'MASTERS',   'Manage registered vehicles'),
    ('FUEL_RATE_MANAGE',        'MASTERS',   'Set fuel rates'),
    ('USER_MANAGE',             'ADMIN',     'Create / deactivate users and reset credentials'),
    ('PERMISSION_MANAGE',       'ADMIN',     'Configure role and user permissions'),
    ('CREDIT_LIMIT_CHANGE',     'CREDIT',    'Set or change customer credit limits'),
    ('CREDIT_LIMIT_EXCEPTION',  'CREDIT',    'Authorise temporary credit-limit exceptions'),
    ('AUDIT_VIEW',              'ADMIN',     'View audit logs'),
    ('SETTINGS_MANAGE',         'ADMIN',     'Manage system settings'),
    ('NOTIFICATION_CONFIG',     'ADMIN',     'Configure notification channels and templates'),
    ('BACKUP_MANAGE',           'ADMIN',     'Trigger and view backups');

-- Default role -> permission mapping
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'DASHBOARD_VIEW','INDENT_CREATE','BILL_APPROVE_OWN','PAYMENT_SUBMIT','LEDGER_VIEW_OWN')
WHERE r.code = 'CUSTOMER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'DASHBOARD_VIEW','INDENT_VIEW_ALL','INDENT_PROCESS','CREDIT_TXN_CREATE','CREDIT_TXN_VIEW_ALL','PAYMENT_RECORD')
WHERE r.code = 'CASHIER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'DASHBOARD_VIEW','INDENT_VIEW_ALL','CREDIT_TXN_VIEW_ALL','DISPUTE_RESOLVE','PAYMENT_VIEW_ALL','PAYMENT_VERIFY',
    'LEDGER_VIEW_ALL','ADJUSTMENT_CREATE','REPORT_VIEW','REPORT_FINANCIAL','REPORT_EXPORT')
WHERE r.code = 'MANAGER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN';

-- Fuel
INSERT INTO fuel_types (code, name) VALUES ('PETROL', 'Petrol'), ('DIESEL', 'Diesel');

-- System settings
INSERT INTO system_settings (key, value, description) VALUES
    ('PUMP_NAME',                     'My Petrol Pump',  'Shown on statements and reports'),
    ('CUSTOMER_APPROVAL_TIMEOUT_HRS', '48',              'Hours after which an unanswered bill becomes NO_RESPONSE'),
    ('CREDIT_LIMIT_WARN_PERCENT',     '80',              'Notify customer/admin when utilisation crosses this %'),
    ('OVER_LIMIT_INDENT_ACTION',      'HOLD',            'HOLD = indent waits for admin exception; BLOCK = rejected'),
    ('PAYMENT_REMINDER_DAYS',         '7',               'Days between outstanding payment reminders'),
    ('MAX_UPLOAD_MB',                 '10',              'Maximum bill / proof upload size'),
    ('BACKUP_RETENTION_DAYS',         '30',              'How long automated backups are kept');

-- Notification events
INSERT INTO notification_settings (event_type, description, title_template, body_template) VALUES
    ('INDENT_RECEIVED',          'New indent received',              'New indent {indentNo}',             '{customerName} raised {fuelType} indent for {vehicleNo}'),
    ('INDENT_PROCESSED',         'Indent accepted / processed',      'Indent {indentNo} accepted',        'Your indent for {vehicleNo} has been accepted'),
    ('FUEL_SUPPLIED',            'Fuel supplied',                    'Fuel supplied - {txnNo}',           '{quantity} L {fuelType} supplied to {vehicleNo}, amount {amount}'),
    ('BILL_UPLOADED',            'Bill photograph uploaded',         'Bill uploaded - {txnNo}',           'Please review the bill for {vehicleNo}'),
    ('BILL_APPROVAL_PENDING',    'Customer approval pending',        'Approval pending - {txnNo}',        'Bill {txnNo} is awaiting your approval'),
    ('BILL_APPROVED',            'Bill approved by customer',        'Bill approved - {txnNo}',           '{customerName} approved bill {txnNo}'),
    ('BILL_REJECTED',            'Bill rejected by customer',        'Bill rejected - {txnNo}',           '{customerName} rejected bill {txnNo}: {reason}'),
    ('DISPUTE_RESOLVED',         'Dispute resolved',                 'Dispute {disputeNo} resolved',      'Resolution: {resolution}'),
    ('PAYMENT_SUBMITTED',        'Payment submitted by customer',    'Payment claim {submissionNo}',      '{customerName} submitted {amount} via {method}'),
    ('CASHIER_PAYMENT_RECORDED', 'Cashier payment entry completed',  'Payment recorded {entryNo}',        '{amount} recorded for {customerName}'),
    ('PAYMENT_AWAITING_VERIFY',  'Payment awaiting verification',    'Verify payment {paymentNo}',        '{amount} from {customerName} awaits verification'),
    ('PAYMENT_VERIFIED',         'Payment verified',                 'Payment verified {paymentNo}',      '{amount} verified. Outstanding now {outstanding}'),
    ('PAYMENT_REJECTED',         'Payment rejected',                 'Payment rejected {paymentNo}',      'Reason: {reason}'),
    ('PAYMENT_PROOF_REQUESTED',  'Additional proof requested',       'More proof needed {paymentNo}',     '{remarks}'),
    ('CREDIT_LIMIT_APPROACHING', 'Credit limit approaching',         'Credit limit {percent}% used',      'Available credit: {available}'),
    ('CREDIT_LIMIT_EXCEEDED',    'Credit limit exceeded',            'Credit limit exceeded',             '{customerName} exceeded limit; indent {indentNo} on hold'),
    ('OUTSTANDING_REMINDER',     'Outstanding payment reminder',     'Payment reminder',                  'Your outstanding balance is {outstanding}');
