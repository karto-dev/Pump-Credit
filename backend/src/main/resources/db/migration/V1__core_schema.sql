-- =====================================================================
-- Petrol Pump Credit Management System - core schema
-- Money: NUMERIC(14,2) in INR. Quantity: NUMERIC(12,3) litres.
-- All timestamps are TIMESTAMPTZ. Financial tables are never hard-deleted.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Generic guards
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_block_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Deleting rows from % is not allowed (financial record)', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fn_block_update_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fn_touch_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Human-readable business numbers
CREATE SEQUENCE seq_customer_code START 1001;
CREATE SEQUENCE seq_indent_no START 1;
CREATE SEQUENCE seq_credit_txn_no START 1;
CREATE SEQUENCE seq_payment_submission_no START 1;
CREATE SEQUENCE seq_cashier_payment_no START 1;
CREATE SEQUENCE seq_payment_no START 1;
CREATE SEQUENCE seq_adjustment_no START 1;
CREATE SEQUENCE seq_dispute_no START 1;

-- ---------------------------------------------------------------------
-- Access control
-- ---------------------------------------------------------------------
CREATE TABLE roles (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(30)  NOT NULL UNIQUE,          -- CUSTOMER, CASHIER, MANAGER, ADMIN
    name         VARCHAR(60)  NOT NULL,
    description  VARCHAR(255),
    is_system    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE permissions (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(60)  NOT NULL UNIQUE,
    module       VARCHAR(40)  NOT NULL,
    description  VARCHAR(255) NOT NULL
);

CREATE TABLE role_permissions (
    role_id        BIGINT NOT NULL REFERENCES roles(id),
    permission_id  BIGINT NOT NULL REFERENCES permissions(id),
    granted_by     BIGINT,
    granted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);

-- ---------------------------------------------------------------------
-- Customers (credit accounts)
-- ---------------------------------------------------------------------
CREATE TABLE customers (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_code         VARCHAR(20)   NOT NULL UNIQUE DEFAULT ('CUST-' || nextval('seq_customer_code')),
    name                  VARCHAR(150)  NOT NULL,
    business_name         VARCHAR(150),
    contact_person        VARCHAR(120),
    phone                 VARCHAR(15)   NOT NULL,
    alternate_phone       VARCHAR(15),
    email                 VARCHAR(150),
    address               TEXT,
    city                  VARCHAR(80),
    state                 VARCHAR(80),
    pincode               VARCHAR(10),
    gstin                 VARCHAR(15),
    pan                   VARCHAR(10),
    credit_limit          NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (credit_limit >= 0),
    credit_period_days    INTEGER       NOT NULL DEFAULT 30 CHECK (credit_period_days >= 0),
    opening_balance       NUMERIC(14,2) NOT NULL DEFAULT 0,
    opening_balance_date  DATE,
    status                VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
                          CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    created_by            BIGINT,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by            BIGINT,
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               INTEGER       NOT NULL DEFAULT 0
);
CREATE INDEX ix_customers_name ON customers (lower(name));
CREATE INDEX ix_customers_phone ON customers (phone);

-- ---------------------------------------------------------------------
-- Users (all four roles). A CUSTOMER user is bound to exactly one customer.
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username               VARCHAR(60)  NOT NULL,
    full_name              VARCHAR(120) NOT NULL,
    email                  VARCHAR(150),
    phone                  VARCHAR(15),
    password_hash          VARCHAR(100) NOT NULL,
    role_id                BIGINT       NOT NULL REFERENCES roles(id),
    customer_id            BIGINT       REFERENCES customers(id),
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password   BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts  INTEGER      NOT NULL DEFAULT 0,
    locked_until           TIMESTAMPTZ,
    last_login_at          TIMESTAMPTZ,
    password_changed_at    TIMESTAMPTZ,
    created_by             BIGINT REFERENCES users(id),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by             BIGINT REFERENCES users(id),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                INTEGER      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_users_username ON users (lower(username));
CREATE INDEX ix_users_customer ON users (customer_id);

ALTER TABLE role_permissions ADD CONSTRAINT fk_role_perm_granted_by FOREIGN KEY (granted_by) REFERENCES users(id);
ALTER TABLE customers ADD CONSTRAINT fk_customers_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE customers ADD CONSTRAINT fk_customers_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- Per-user grant/revoke on top of the role's permissions (admin-configurable).
CREATE TABLE user_permission_overrides (
    user_id        BIGINT      NOT NULL REFERENCES users(id),
    permission_id  BIGINT      NOT NULL REFERENCES permissions(id),
    granted        BOOLEAN     NOT NULL,              -- TRUE = grant, FALSE = revoke
    reason         VARCHAR(255),
    valid_until    TIMESTAMPTZ,
    set_by         BIGINT      NOT NULL REFERENCES users(id),
    set_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, permission_id)
);

CREATE TABLE user_sessions (
    id            UUID        PRIMARY KEY,           -- JWT "jti"
    user_id       BIGINT      NOT NULL REFERENCES users(id),
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    revoked_reason VARCHAR(60),
    ip_address    VARCHAR(45),
    user_agent    VARCHAR(300)
);
CREATE INDEX ix_user_sessions_user ON user_sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE password_reset_tokens (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users(id),
    token_hash   VARCHAR(100) NOT NULL UNIQUE,
    requested_by BIGINT      REFERENCES users(id),   -- admin who triggered the reset, if any
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Vehicles and fuel
-- ---------------------------------------------------------------------
CREATE TABLE fuel_types (
    code        VARCHAR(20) PRIMARY KEY,               -- PETROL, DIESEL
    name        VARCHAR(40) NOT NULL,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE TABLE fuel_rates (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fuel_type       VARCHAR(20)   NOT NULL REFERENCES fuel_types(code),
    rate_per_litre  NUMERIC(10,2) NOT NULL CHECK (rate_per_litre > 0),
    effective_from  TIMESTAMPTZ   NOT NULL,
    set_by          BIGINT        REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (fuel_type, effective_from)
);

CREATE TABLE vehicles (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id      BIGINT      NOT NULL REFERENCES customers(id),
    vehicle_number   VARCHAR(15) NOT NULL,              -- stored normalised: upper-case, no spaces
    vehicle_type     VARCHAR(30),                       -- TRUCK, CAR, TRACTOR, ...
    default_fuel     VARCHAR(20) REFERENCES fuel_types(code),
    description      VARCHAR(150),
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by       BIGINT      REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       BIGINT      REFERENCES users(id),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_vehicle_number_normalised CHECK (vehicle_number = upper(vehicle_number) AND vehicle_number !~ '\s')
);
CREATE UNIQUE INDEX ux_vehicles_active_number ON vehicles (vehicle_number) WHERE is_active;
CREATE INDEX ix_vehicles_customer ON vehicles (customer_id);

-- ---------------------------------------------------------------------
-- Credit limits
-- ---------------------------------------------------------------------
CREATE TABLE credit_limit_changes (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id  BIGINT        NOT NULL REFERENCES customers(id),
    old_limit    NUMERIC(14,2) NOT NULL,
    new_limit    NUMERIC(14,2) NOT NULL CHECK (new_limit >= 0),
    reason       VARCHAR(500)  NOT NULL,
    changed_by   BIGINT        NOT NULL REFERENCES users(id),
    changed_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_credit_limit_changes_customer ON credit_limit_changes (customer_id, changed_at DESC);

CREATE TABLE credit_limit_exceptions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     BIGINT        NOT NULL REFERENCES customers(id),
    extra_limit     NUMERIC(14,2) NOT NULL CHECK (extra_limit > 0),
    valid_from      TIMESTAMPTZ   NOT NULL,
    valid_until     TIMESTAMPTZ   NOT NULL,
    reason          VARCHAR(500)  NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','EXPIRED','REVOKED')),
    authorised_by   BIGINT        NOT NULL REFERENCES users(id),
    authorised_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    revoked_by      BIGINT        REFERENCES users(id),
    revoked_at      TIMESTAMPTZ,
    revoke_reason   VARCHAR(500),
    CHECK (valid_until > valid_from)
);
CREATE INDEX ix_credit_limit_exceptions_active ON credit_limit_exceptions (customer_id) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------
-- Indents
-- ---------------------------------------------------------------------
CREATE TABLE indents (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    indent_no            VARCHAR(20)   NOT NULL UNIQUE DEFAULT ('IND-' || lpad(nextval('seq_indent_no')::text, 7, '0')),
    customer_id          BIGINT        NOT NULL REFERENCES customers(id),
    vehicle_id           BIGINT        NOT NULL REFERENCES vehicles(id),
    fuel_type            VARCHAR(20)   NOT NULL REFERENCES fuel_types(code),
    requested_quantity   NUMERIC(12,3) CHECK (requested_quantity > 0),
    requested_amount     NUMERIC(14,2) CHECK (requested_amount > 0),
    full_tank            BOOLEAN       NOT NULL DEFAULT FALSE,
    instructions         VARCHAR(500),
    status               VARCHAR(30)   NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING','ON_HOLD_CREDIT_LIMIT','ACCEPTED','COMPLETED','CANCELLED','REJECTED','EXPIRED')),
    hold_reason          VARCHAR(255),
    credit_exception_id  BIGINT        REFERENCES credit_limit_exceptions(id),
    accepted_by          BIGINT        REFERENCES users(id),
    accepted_at          TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    cancelled_by         BIGINT        REFERENCES users(id),
    cancelled_at         TIMESTAMPTZ,
    cancel_reason        VARCHAR(255),
    created_by           BIGINT        NOT NULL REFERENCES users(id),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by           BIGINT        REFERENCES users(id),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version              INTEGER       NOT NULL DEFAULT 0,
    CHECK (requested_quantity IS NOT NULL OR requested_amount IS NOT NULL OR full_tank)
);
CREATE INDEX ix_indents_customer ON indents (customer_id, created_at DESC);
CREATE INDEX ix_indents_status ON indents (status, created_at);

-- ---------------------------------------------------------------------
-- Credit transactions (supplied credit sales)
-- approval_status is tracked independently; a sale is in the ledger regardless.
-- ---------------------------------------------------------------------
CREATE TABLE credit_transactions (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    txn_no            VARCHAR(20)   NOT NULL UNIQUE DEFAULT ('CRS-' || lpad(nextval('seq_credit_txn_no')::text, 8, '0')),
    indent_id         BIGINT        REFERENCES indents(id),
    customer_id       BIGINT        NOT NULL REFERENCES customers(id),
    vehicle_id        BIGINT        NOT NULL REFERENCES vehicles(id),
    fuel_type         VARCHAR(20)   NOT NULL REFERENCES fuel_types(code),
    quantity          NUMERIC(12,3) NOT NULL CHECK (quantity > 0),
    rate_per_litre    NUMERIC(10,2) NOT NULL CHECK (rate_per_litre > 0),
    amount            NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    supplied_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    physical_bill_no  VARCHAR(40),
    remarks           VARCHAR(500),
    cashier_id        BIGINT        NOT NULL REFERENCES users(id),
    approval_status   VARCHAR(30)   NOT NULL DEFAULT 'AWAITING_CUSTOMER_APPROVAL'
                      CHECK (approval_status IN ('AWAITING_CUSTOMER_APPROVAL','CUSTOMER_APPROVED','CUSTOMER_REJECTED',
                                                 'NO_RESPONSE','UNDER_DISPUTE','RESOLVED')),
    approval_updated_at TIMESTAMPTZ,
    status            VARCHAR(20)   NOT NULL DEFAULT 'POSTED'
                      CHECK (status IN ('POSTED','REVERSED')),
    reversed_by_adjustment_id BIGINT,
    created_by        BIGINT        NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by        BIGINT        REFERENCES users(id),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           INTEGER       NOT NULL DEFAULT 0,
    -- amount must equal quantity x rate (rounded to paise)
    CONSTRAINT ck_credit_txn_amount CHECK (amount = round(quantity * rate_per_litre, 2))
);
CREATE UNIQUE INDEX ux_credit_txn_indent ON credit_transactions (indent_id) WHERE indent_id IS NOT NULL;
CREATE INDEX ix_credit_txn_customer ON credit_transactions (customer_id, supplied_at DESC);
CREATE INDEX ix_credit_txn_supplied ON credit_transactions (supplied_at);
CREATE INDEX ix_credit_txn_cashier ON credit_transactions (cashier_id, supplied_at);
CREATE INDEX ix_credit_txn_vehicle ON credit_transactions (vehicle_id, supplied_at);
CREATE INDEX ix_credit_txn_approval ON credit_transactions (approval_status);

CREATE TABLE bill_attachments (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    credit_transaction_id  BIGINT       NOT NULL REFERENCES credit_transactions(id),
    storage_key            VARCHAR(300) NOT NULL UNIQUE,  -- path/S3 key; never a public URL
    original_file_name     VARCHAR(200),
    content_type           VARCHAR(80)  NOT NULL,
    size_bytes             BIGINT       NOT NULL CHECK (size_bytes > 0),
    sha256                 CHAR(64)     NOT NULL,
    uploaded_by            BIGINT       NOT NULL REFERENCES users(id),
    uploaded_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_bill_attachments_txn ON bill_attachments (credit_transaction_id);

-- Full history of approval-status changes (current value lives on credit_transactions).
CREATE TABLE customer_approvals (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    credit_transaction_id  BIGINT       NOT NULL REFERENCES credit_transactions(id),
    from_status            VARCHAR(30),
    to_status              VARCHAR(30)  NOT NULL,
    reason                 VARCHAR(500),
    acted_by               BIGINT       REFERENCES users(id),  -- NULL = system (e.g. NO_RESPONSE timeout)
    acted_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (to_status <> 'CUSTOMER_REJECTED' OR reason IS NOT NULL)
);
CREATE INDEX ix_customer_approvals_txn ON customer_approvals (credit_transaction_id, acted_at);

CREATE TABLE disputes (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dispute_no             VARCHAR(20)  NOT NULL UNIQUE DEFAULT ('DSP-' || lpad(nextval('seq_dispute_no')::text, 6, '0')),
    credit_transaction_id  BIGINT       NOT NULL REFERENCES credit_transactions(id),
    customer_id            BIGINT       NOT NULL REFERENCES customers(id),
    raised_by              BIGINT       NOT NULL REFERENCES users(id),
    raised_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason                 VARCHAR(1000) NOT NULL,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'OPEN'
                           CHECK (status IN ('OPEN','UNDER_INVESTIGATION','RESOLVED')),
    assigned_to            BIGINT       REFERENCES users(id),
    resolution_type        VARCHAR(30)  CHECK (resolution_type IN ('BILL_UPHELD','ADJUSTED','REVERSED')),
    resolution_notes       VARCHAR(1000),
    adjustment_id          BIGINT,
    resolved_by            BIGINT       REFERENCES users(id),
    resolved_at            TIMESTAMPTZ,
    CHECK (status <> 'RESOLVED' OR (resolution_type IS NOT NULL AND resolution_notes IS NOT NULL AND resolved_by IS NOT NULL))
);
CREATE UNIQUE INDEX ux_disputes_open_per_txn ON disputes (credit_transaction_id) WHERE status <> 'RESOLVED';
CREATE INDEX ix_disputes_status ON disputes (status);

-- ---------------------------------------------------------------------
-- Payments
--   payment_submissions     : customer's claim ("I paid")
--   cashier_payment_entries : cashier's record of money received
--   payments                : one canonical payment linking the two, moves through verification
--   payment_verifications   : every verify / reject / proof-request action
--   payment_allocations     : verified money applied to credit sales (remainder = advance)
-- ---------------------------------------------------------------------
CREATE TABLE payment_submissions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    submission_no   VARCHAR(20)   NOT NULL UNIQUE DEFAULT ('PSUB-' || lpad(nextval('seq_payment_submission_no')::text, 7, '0')),
    customer_id     BIGINT        NOT NULL REFERENCES customers(id),
    amount          NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    payment_date    DATE          NOT NULL,
    method          VARCHAR(10)   NOT NULL CHECK (method IN ('UPI','NEFT','IMPS','RTGS','CASH','CHEQUE')),
    reference_no    VARCHAR(60),
    remarks         VARCHAR(500),
    submitted_by    BIGINT        NOT NULL REFERENCES users(id),
    submitted_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    cancelled_by    BIGINT        REFERENCES users(id),
    cancelled_at    TIMESTAMPTZ,
    cancel_reason   VARCHAR(255),
    CHECK (method = 'CASH' OR reference_no IS NOT NULL)
);
CREATE INDEX ix_payment_submissions_customer ON payment_submissions (customer_id, submitted_at DESC);

CREATE TABLE cashier_payment_entries (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_no        VARCHAR(20)   NOT NULL UNIQUE DEFAULT ('CPE-' || lpad(nextval('seq_cashier_payment_no')::text, 7, '0')),
    customer_id     BIGINT        NOT NULL REFERENCES customers(id),
    amount          NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    payment_date    DATE          NOT NULL,
    method          VARCHAR(10)   NOT NULL CHECK (method IN ('UPI','NEFT','IMPS','RTGS','CASH','CHEQUE')),
    reference_no    VARCHAR(60),
    cheque_bank     VARCHAR(80),
    remarks         VARCHAR(500),
    recorded_by     BIGINT        NOT NULL REFERENCES users(id),
    recorded_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (method = 'CASH' OR reference_no IS NOT NULL)
);
CREATE INDEX ix_cashier_payment_entries_customer ON cashier_payment_entries (customer_id, recorded_at DESC);

CREATE TABLE payments (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_no            VARCHAR(20)   NOT NULL UNIQUE DEFAULT ('PAY-' || lpad(nextval('seq_payment_no')::text, 7, '0')),
    customer_id           BIGINT        NOT NULL REFERENCES customers(id),
    submission_id         BIGINT        UNIQUE REFERENCES payment_submissions(id),
    cashier_entry_id      BIGINT        UNIQUE REFERENCES cashier_payment_entries(id),
    amount                NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    payment_date          DATE          NOT NULL,
    method                VARCHAR(10)   NOT NULL CHECK (method IN ('UPI','NEFT','IMPS','RTGS','CASH','CHEQUE')),
    reference_no          VARCHAR(60),
    reference_norm        VARCHAR(60) GENERATED ALWAYS AS (upper(regexp_replace(reference_no, '\s', '', 'g'))) STORED,
    status                VARCHAR(30)   NOT NULL
                          CHECK (status IN ('CUSTOMER_SUBMITTED','AWAITING_CASHIER_ENTRY','CASHIER_RECORDED',
                                            'AWAITING_VERIFICATION','VERIFIED','REJECTED','ADDITIONAL_PROOF_REQUIRED',
                                            'DUPLICATE_PAYMENT','CANCELLED')),
    duplicate_flag        BOOLEAN       NOT NULL DEFAULT FALSE,
    duplicate_of_id       BIGINT        REFERENCES payments(id),
    verified_amount       NUMERIC(14,2) CHECK (verified_amount > 0),
    verified_by           BIGINT        REFERENCES users(id),
    verified_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               INTEGER       NOT NULL DEFAULT 0,
    CHECK (submission_id IS NOT NULL OR cashier_entry_id IS NOT NULL),
    CHECK (status <> 'VERIFIED' OR (verified_amount IS NOT NULL AND verified_by IS NOT NULL AND verified_at IS NOT NULL))
);
-- The same UTR/reference can be VERIFIED only once (cash has no reference).
CREATE UNIQUE INDEX ux_payments_verified_reference ON payments (method, reference_norm)
    WHERE status = 'VERIFIED' AND reference_norm IS NOT NULL;
CREATE INDEX ix_payments_reference ON payments (reference_norm);
CREATE INDEX ix_payments_customer ON payments (customer_id, created_at DESC);
CREATE INDEX ix_payments_status ON payments (status);

-- Proof files for submissions and cashier entries.
CREATE TABLE payment_proofs (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id          BIGINT       REFERENCES payments(id),
    submission_id       BIGINT       REFERENCES payment_submissions(id),
    cashier_entry_id    BIGINT       REFERENCES cashier_payment_entries(id),
    storage_key         VARCHAR(300) NOT NULL UNIQUE,
    original_file_name  VARCHAR(200),
    content_type        VARCHAR(80)  NOT NULL,
    size_bytes          BIGINT       NOT NULL CHECK (size_bytes > 0),
    sha256              CHAR(64)     NOT NULL,
    uploaded_by         BIGINT       NOT NULL REFERENCES users(id),
    uploaded_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(payment_id, submission_id, cashier_entry_id) >= 1)
);
CREATE INDEX ix_payment_proofs_payment ON payment_proofs (payment_id);
CREATE INDEX ix_payment_proofs_sha ON payment_proofs (sha256);   -- same screenshot reused = duplicate hint

CREATE TABLE payment_verifications (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id       BIGINT        NOT NULL REFERENCES payments(id),
    action           VARCHAR(30)   NOT NULL
                     CHECK (action IN ('VERIFIED','REJECTED','ADDITIONAL_PROOF_REQUIRED','MARKED_DUPLICATE','CANCELLED','REOPENED')),
    from_status      VARCHAR(30)   NOT NULL,
    to_status        VARCHAR(30)   NOT NULL,
    verified_amount  NUMERIC(14,2),
    evidence_checked VARCHAR(255),                  -- e.g. "Bank statement 04-11-2026, HDFC"
    remarks          VARCHAR(1000) NOT NULL,
    acted_by         BIGINT        NOT NULL REFERENCES users(id),
    acted_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_payment_verifications_payment ON payment_verifications (payment_id, acted_at);

-- A cashier can never verify a payment they recorded themselves.
CREATE OR REPLACE FUNCTION fn_payment_verification_maker_checker() RETURNS trigger AS $$
DECLARE
    v_recorded_by BIGINT;
BEGIN
    SELECT cpe.recorded_by INTO v_recorded_by
      FROM payments p JOIN cashier_payment_entries cpe ON cpe.id = p.cashier_entry_id
     WHERE p.id = NEW.payment_id;
    IF NEW.action = 'VERIFIED' AND v_recorded_by IS NOT NULL AND v_recorded_by = NEW.acted_by THEN
        RAISE EXCEPTION 'Maker-checker violation: user % recorded this payment and cannot verify it', NEW.acted_by;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_verification_maker_checker
    BEFORE INSERT ON payment_verifications
    FOR EACH ROW EXECUTE FUNCTION fn_payment_verification_maker_checker();

CREATE TABLE payment_allocations (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id             BIGINT        NOT NULL REFERENCES payments(id),
    credit_transaction_id  BIGINT        REFERENCES credit_transactions(id),  -- NULL = advance / on-account
    amount                 NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    allocated_by           BIGINT        REFERENCES users(id),
    allocated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_payment_allocations_payment ON payment_allocations (payment_id);
CREATE INDEX ix_payment_allocations_txn ON payment_allocations (credit_transaction_id);

-- ---------------------------------------------------------------------
-- Adjustments (debit increases outstanding, credit reduces it)
-- ---------------------------------------------------------------------
CREATE TABLE adjustments (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    adjustment_no          VARCHAR(20)   NOT NULL UNIQUE DEFAULT ('ADJ-' || lpad(nextval('seq_adjustment_no')::text, 6, '0')),
    customer_id            BIGINT        NOT NULL REFERENCES customers(id),
    adj_type               VARCHAR(10)   NOT NULL CHECK (adj_type IN ('DEBIT','CREDIT')),
    amount                 NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    reason                 VARCHAR(1000) NOT NULL,
    credit_transaction_id  BIGINT        REFERENCES credit_transactions(id),
    dispute_id             BIGINT        REFERENCES disputes(id),
    status                 VARCHAR(20)   NOT NULL DEFAULT 'PENDING_APPROVAL'
                           CHECK (status IN ('PENDING_APPROVAL','APPROVED','REJECTED')),
    created_by             BIGINT        NOT NULL REFERENCES users(id),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    approved_by            BIGINT        REFERENCES users(id),
    approved_at            TIMESTAMPTZ,
    approval_remarks       VARCHAR(500),
    CHECK (approved_by IS NULL OR approved_by <> created_by)
);
CREATE INDEX ix_adjustments_customer ON adjustments (customer_id, created_at DESC);

ALTER TABLE credit_transactions ADD CONSTRAINT fk_credit_txn_reversal
    FOREIGN KEY (reversed_by_adjustment_id) REFERENCES adjustments(id);
ALTER TABLE disputes ADD CONSTRAINT fk_disputes_adjustment
    FOREIGN KEY (adjustment_id) REFERENCES adjustments(id);

-- ---------------------------------------------------------------------
-- Ledger (append-only, source of truth for confirmed outstanding)
--   Closing = Opening + Credit Sales - Verified Recoveries + Debit Adj - Credit Adj
--   debit  column increases outstanding, credit column reduces it.
-- ---------------------------------------------------------------------
CREATE TABLE ledger_entries (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id      BIGINT        NOT NULL REFERENCES customers(id),
    entry_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    entry_type       VARCHAR(20)   NOT NULL
                     CHECK (entry_type IN ('OPENING_BALANCE','CREDIT_SALE','PAYMENT','DEBIT_ADJUSTMENT','CREDIT_ADJUSTMENT')),
    debit            NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit           NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    running_balance  NUMERIC(14,2) NOT NULL,
    credit_transaction_id BIGINT   REFERENCES credit_transactions(id),
    payment_id       BIGINT        REFERENCES payments(id),
    adjustment_id    BIGINT        REFERENCES adjustments(id),
    narration        VARCHAR(500)  NOT NULL,
    created_by       BIGINT        REFERENCES users(id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK ((debit > 0) <> (credit > 0) OR entry_type = 'OPENING_BALANCE'),
    CHECK (num_nonnulls(credit_transaction_id, payment_id, adjustment_id) <= 1)
);
CREATE INDEX ix_ledger_customer ON ledger_entries (customer_id, id);
CREATE INDEX ix_ledger_entry_at ON ledger_entries (entry_at);
-- Each source document posts to the ledger at most once.
CREATE UNIQUE INDEX ux_ledger_credit_txn ON ledger_entries (credit_transaction_id) WHERE credit_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX ux_ledger_payment ON ledger_entries (payment_id) WHERE payment_id IS NOT NULL;
CREATE UNIQUE INDEX ux_ledger_adjustment ON ledger_entries (adjustment_id) WHERE adjustment_id IS NOT NULL;
-- Only verified payments may reach the ledger.
CREATE OR REPLACE FUNCTION fn_ledger_payment_must_be_verified() RETURNS trigger AS $$
BEGIN
    IF NEW.payment_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM payments WHERE id = NEW.payment_id AND status = 'VERIFIED') THEN
        RAISE EXCEPTION 'Payment % is not VERIFIED and cannot be posted to the ledger', NEW.payment_id;
    END IF;
    IF NEW.adjustment_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM adjustments WHERE id = NEW.adjustment_id AND status = 'APPROVED') THEN
        RAISE EXCEPTION 'Adjustment % is not APPROVED and cannot be posted to the ledger', NEW.adjustment_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_payment_verified
    BEFORE INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION fn_ledger_payment_must_be_verified();

-- ---------------------------------------------------------------------
-- Notifications (in-app now; SMS / WhatsApp channels later)
-- ---------------------------------------------------------------------
CREATE TABLE notification_settings (
    event_type        VARCHAR(50)  PRIMARY KEY,
    description       VARCHAR(255) NOT NULL,
    in_app_enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    sms_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    whatsapp_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
    title_template    VARCHAR(200) NOT NULL,
    body_template     VARCHAR(1000) NOT NULL,
    updated_by        BIGINT       REFERENCES users(id),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE notifications (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users(id),
    event_type    VARCHAR(50)  NOT NULL REFERENCES notification_settings(event_type),
    title         VARCHAR(200) NOT NULL,
    message       VARCHAR(1000) NOT NULL,
    entity_type   VARCHAR(40),
    entity_id     BIGINT,
    is_read       BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user_unread ON notifications (user_id, created_at DESC) WHERE NOT is_read;

-- Outbox for external channels (SMS/WhatsApp). A worker will drain this in a later phase.
CREATE TABLE notification_deliveries (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id  BIGINT      NOT NULL REFERENCES notifications(id),
    channel          VARCHAR(20) NOT NULL CHECK (channel IN ('SMS','WHATSAPP','EMAIL')),
    destination      VARCHAR(150) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','SENT','FAILED')),
    attempts         INTEGER     NOT NULL DEFAULT 0,
    last_error       VARCHAR(500),
    provider_ref     VARCHAR(100),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ
);
CREATE INDEX ix_notification_deliveries_queued ON notification_deliveries (created_at) WHERE status = 'QUEUED';

-- ---------------------------------------------------------------------
-- Audit log (append-only)
-- ---------------------------------------------------------------------
CREATE TABLE audit_logs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_user_id BIGINT       REFERENCES users(id),
    actor_role    VARCHAR(30),
    action        VARCHAR(60)  NOT NULL,     -- e.g. CREDIT_LIMIT_CHANGED, PAYMENT_VERIFIED, LOGIN_SUCCESS
    entity_type   VARCHAR(40)  NOT NULL,
    entity_id     VARCHAR(40),
    old_value     JSONB,
    new_value     JSONB,
    reason        VARCHAR(1000),
    ip_address    VARCHAR(45),
    user_agent    VARCHAR(300)
);
CREATE INDEX ix_audit_entity ON audit_logs (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_actor ON audit_logs (actor_user_id, occurred_at DESC);
CREATE INDEX ix_audit_occurred ON audit_logs (occurred_at DESC);

-- ---------------------------------------------------------------------
-- System settings and backups
-- ---------------------------------------------------------------------
CREATE TABLE system_settings (
    key          VARCHAR(80)  PRIMARY KEY,
    value        VARCHAR(500) NOT NULL,
    description  VARCHAR(255),
    updated_by   BIGINT       REFERENCES users(id),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE backup_runs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','SUCCESS','FAILED')),
    location      VARCHAR(300),
    size_bytes    BIGINT,
    triggered_by  BIGINT      REFERENCES users(id),  -- NULL = scheduled
    error         VARCHAR(1000)
);

-- ---------------------------------------------------------------------
-- updated_at maintenance
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_customers_touch   BEFORE UPDATE ON customers           FOR EACH ROW EXECUTE FUNCTION fn_touch_updated_at();
CREATE TRIGGER trg_users_touch       BEFORE UPDATE ON users               FOR EACH ROW EXECUTE FUNCTION fn_touch_updated_at();
CREATE TRIGGER trg_vehicles_touch    BEFORE UPDATE ON vehicles            FOR EACH ROW EXECUTE FUNCTION fn_touch_updated_at();
CREATE TRIGGER trg_indents_touch     BEFORE UPDATE ON indents             FOR EACH ROW EXECUTE FUNCTION fn_touch_updated_at();
CREATE TRIGGER trg_credit_txn_touch  BEFORE UPDATE ON credit_transactions FOR EACH ROW EXECUTE FUNCTION fn_touch_updated_at();
CREATE TRIGGER trg_payments_touch    BEFORE UPDATE ON payments            FOR EACH ROW EXECUTE FUNCTION fn_touch_updated_at();

-- ---------------------------------------------------------------------
-- No permanent deletion of financial records
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_no_delete_credit_txn        BEFORE DELETE ON credit_transactions      FOR EACH ROW EXECUTE FUNCTION fn_block_delete();
CREATE TRIGGER trg_no_delete_bill_attachments  BEFORE DELETE ON bill_attachments         FOR EACH ROW EXECUTE FUNCTION fn_block_delete();
CREATE TRIGGER trg_no_delete_disputes          BEFORE DELETE ON disputes                 FOR EACH ROW EXECUTE FUNCTION fn_block_delete();
CREATE TRIGGER trg_no_delete_pay_submissions   BEFORE DELETE ON payment_submissions      FOR EACH ROW EXECUTE FUNCTION fn_block_delete();
CREATE TRIGGER trg_no_delete_cashier_entries   BEFORE DELETE ON cashier_payment_entries  FOR EACH ROW EXECUTE FUNCTION fn_block_delete();
CREATE TRIGGER trg_no_delete_payments          BEFORE DELETE ON payments                 FOR EACH ROW EXECUTE FUNCTION fn_block_delete();
CREATE TRIGGER trg_no_delete_payment_proofs    BEFORE DELETE ON payment_proofs           FOR EACH ROW EXECUTE FUNCTION fn_block_delete();
CREATE TRIGGER trg_no_delete_payment_alloc     BEFORE DELETE ON payment_allocations      FOR EACH ROW EXECUTE FUNCTION fn_block_delete();
CREATE TRIGGER trg_no_delete_adjustments       BEFORE DELETE ON adjustments              FOR EACH ROW EXECUTE FUNCTION fn_block_delete();
CREATE TRIGGER trg_no_delete_credit_limit_chg  BEFORE DELETE ON credit_limit_changes     FOR EACH ROW EXECUTE FUNCTION fn_block_delete();
CREATE TRIGGER trg_no_delete_credit_limit_exc  BEFORE DELETE ON credit_limit_exceptions  FOR EACH ROW EXECUTE FUNCTION fn_block_delete();
CREATE TRIGGER trg_no_delete_customers         BEFORE DELETE ON customers                FOR EACH ROW EXECUTE FUNCTION fn_block_delete();

CREATE TRIGGER trg_append_only_ledger        BEFORE UPDATE OR DELETE ON ledger_entries        FOR EACH ROW EXECUTE FUNCTION fn_block_update_delete();
CREATE TRIGGER trg_append_only_audit         BEFORE UPDATE OR DELETE ON audit_logs            FOR EACH ROW EXECUTE FUNCTION fn_block_update_delete();
CREATE TRIGGER trg_append_only_approvals     BEFORE UPDATE OR DELETE ON customer_approvals    FOR EACH ROW EXECUTE FUNCTION fn_block_update_delete();
CREATE TRIGGER trg_append_only_verifications BEFORE UPDATE OR DELETE ON payment_verifications FOR EACH ROW EXECUTE FUNCTION fn_block_update_delete();

-- ---------------------------------------------------------------------
-- Balance view: confirmed outstanding comes only from the ledger;
-- unverified claims / pending approvals / disputes are shown separately.
-- ---------------------------------------------------------------------
CREATE VIEW v_customer_balances AS
SELECT
    c.id                                   AS customer_id,
    c.customer_code,
    c.name,
    c.credit_limit,
    COALESCE(l.confirmed_outstanding, 0)   AS confirmed_outstanding,
    COALESCE(ex.extra_limit, 0)            AS active_exception_limit,
    c.credit_limit + COALESCE(ex.extra_limit, 0) - COALESCE(l.confirmed_outstanding, 0) AS available_credit,
    COALESCE(p.unverified_claims, 0)       AS unverified_payment_claims,
    COALESCE(t.pending_approval_count, 0)  AS pending_approval_count,
    COALESCE(t.pending_approval_amount, 0) AS pending_approval_amount,
    COALESCE(t.disputed_count, 0)          AS disputed_count,
    COALESCE(t.disputed_amount, 0)         AS disputed_amount
FROM customers c
LEFT JOIN (
    SELECT customer_id, SUM(debit) - SUM(credit) AS confirmed_outstanding
    FROM ledger_entries GROUP BY customer_id
) l ON l.customer_id = c.id
LEFT JOIN (
    SELECT customer_id, SUM(extra_limit) AS extra_limit
    FROM credit_limit_exceptions
    WHERE status = 'ACTIVE' AND now() BETWEEN valid_from AND valid_until
    GROUP BY customer_id
) ex ON ex.customer_id = c.id
LEFT JOIN (
    SELECT customer_id, SUM(amount) AS unverified_claims
    FROM payments
    WHERE status IN ('CUSTOMER_SUBMITTED','AWAITING_CASHIER_ENTRY','CASHIER_RECORDED',
                     'AWAITING_VERIFICATION','ADDITIONAL_PROOF_REQUIRED')
    GROUP BY customer_id
) p ON p.customer_id = c.id
LEFT JOIN (
    SELECT customer_id,
           COUNT(*) FILTER (WHERE approval_status IN ('AWAITING_CUSTOMER_APPROVAL','NO_RESPONSE')) AS pending_approval_count,
           SUM(amount) FILTER (WHERE approval_status IN ('AWAITING_CUSTOMER_APPROVAL','NO_RESPONSE')) AS pending_approval_amount,
           COUNT(*) FILTER (WHERE approval_status IN ('CUSTOMER_REJECTED','UNDER_DISPUTE')) AS disputed_count,
           SUM(amount) FILTER (WHERE approval_status IN ('CUSTOMER_REJECTED','UNDER_DISPUTE')) AS disputed_amount
    FROM credit_transactions
    WHERE status = 'POSTED'
    GROUP BY customer_id
) t ON t.customer_id = c.id;
