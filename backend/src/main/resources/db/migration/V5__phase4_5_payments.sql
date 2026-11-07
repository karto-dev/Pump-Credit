-- Phases 4-5: payment claims, cashier payment entries, verification and ledger posting.
-- Tables come from V1; this adds settings, events and the guards that keep a verified payment honest.

INSERT INTO system_settings (key, value, description) VALUES
    ('PAYMENT_MAX_BACKDATE_DAYS', '90', 'A payment claim or cashier entry cannot be dated earlier than this many days ago'),
    ('PUMP_UPI_ID',               '',   'UPI ID shown to customers on the Pay screen (blank = hidden)'),
    ('PUMP_BANK_DETAILS',         '',   'Bank account for NEFT/IMPS/RTGS shown to customers (blank = hidden)');

INSERT INTO notification_settings (event_type, description, title_template, body_template) VALUES
    ('PAYMENT_CANCELLED',      'Payment claim withdrawn by customer', 'Payment claim {paymentNo} withdrawn', '{customerName} withdrew {amount}: {reason}'),
    ('PAYMENT_PROOF_ADDED',    'More proof added to a payment',       'New proof on {paymentNo}',            '{customerName} added proof for {amount}');

-- Cash only exists once a cashier has counted it, so a cash payment needs a cashier entry before it is verified.
ALTER TABLE payments ADD CONSTRAINT ck_payments_cash_needs_cashier
    CHECK (status <> 'VERIFIED' OR method <> 'CASH' OR cashier_entry_id IS NOT NULL);
ALTER TABLE payments ADD CONSTRAINT ck_payments_verified_not_above_amount
    CHECK (verified_amount IS NULL OR verified_amount <= amount);

-- A VERIFIED payment is in the ledger: its money fields are frozen and it cannot leave VERIFIED.
-- A wrong verification is corrected with a debit adjustment, never an edit.
CREATE OR REPLACE FUNCTION fn_freeze_verified_payment() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'VERIFIED' AND (
           NEW.status          IS DISTINCT FROM OLD.status
        OR NEW.amount          IS DISTINCT FROM OLD.amount
        OR NEW.verified_amount IS DISTINCT FROM OLD.verified_amount
        OR NEW.method          IS DISTINCT FROM OLD.method
        OR NEW.reference_no    IS DISTINCT FROM OLD.reference_no
        OR NEW.customer_id     IS DISTINCT FROM OLD.customer_id
        OR NEW.payment_date    IS DISTINCT FROM OLD.payment_date
        OR NEW.verified_by     IS DISTINCT FROM OLD.verified_by
        OR NEW.verified_at     IS DISTINCT FROM OLD.verified_at) THEN
        RAISE EXCEPTION 'Payment % is verified and posted to the ledger; raise an adjustment instead', OLD.payment_no;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_freeze_verified_payment BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION fn_freeze_verified_payment();

-- Allocations can never add up to more than the verified amount.
CREATE OR REPLACE FUNCTION fn_payment_allocation_cap() RETURNS trigger AS $$
DECLARE
    v_verified NUMERIC(14,2);
    v_total    NUMERIC(14,2);
BEGIN
    SELECT verified_amount INTO v_verified FROM payments WHERE id = NEW.payment_id AND status = 'VERIFIED';
    IF v_verified IS NULL THEN
        RAISE EXCEPTION 'Payment % is not verified; nothing can be allocated', NEW.payment_id;
    END IF;
    SELECT COALESCE(SUM(amount), 0) + NEW.amount INTO v_total FROM payment_allocations WHERE payment_id = NEW.payment_id;
    IF v_total > v_verified THEN
        RAISE EXCEPTION 'Allocations (%) would exceed the verified amount (%) of payment %', v_total, v_verified, NEW.payment_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_allocation_cap BEFORE INSERT ON payment_allocations
    FOR EACH ROW EXECUTE FUNCTION fn_payment_allocation_cap();
CREATE TRIGGER trg_append_only_allocations BEFORE UPDATE ON payment_allocations
    FOR EACH ROW EXECUTE FUNCTION fn_block_update_delete();

-- Queues: verifier filters by status; cashier looks up a customer's open cash claims.
CREATE INDEX ix_payments_open ON payments (customer_id, status)
    WHERE status IN ('AWAITING_CASHIER_ENTRY','AWAITING_VERIFICATION','ADDITIONAL_PROOF_REQUIRED');
CREATE INDEX ix_payment_proofs_submission ON payment_proofs (submission_id);
CREATE INDEX ix_payment_proofs_entry ON payment_proofs (cashier_entry_id);
