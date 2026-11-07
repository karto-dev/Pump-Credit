-- Phase 3: customer bill approval, NO_RESPONSE timeout, disputes and dispute adjustments

INSERT INTO notification_settings (event_type, description, title_template, body_template) VALUES
    ('DISPUTE_RAISED',        'Bill disputed by customer',          'Dispute {disputeNo} raised',         '{customerName} disputed {txnNo} ({amount}): {reason}'),
    ('DISPUTE_INVESTIGATING', 'Dispute taken up for investigation', 'Dispute {disputeNo} under review',   '{assignedTo} is looking into your dispute on {txnNo}'),
    ('ADJUSTMENT_PENDING',    'Adjustment awaiting approval',       'Approve adjustment {adjustmentNo}',  '{amount} credit for {customerName}: {reason}'),
    ('ADJUSTMENT_REJECTED',   'Adjustment rejected',                'Adjustment {adjustmentNo} rejected', '{remarks}');

-- A posted sale is already in the ledger, so its money fields are frozen. Corrections go through an
-- approved adjustment, never an edit. Approval / dispute / reversal status columns stay updatable.
CREATE OR REPLACE FUNCTION fn_freeze_credit_txn() RETURNS trigger AS $$
BEGIN
    IF NEW.customer_id    IS DISTINCT FROM OLD.customer_id
    OR NEW.vehicle_id     IS DISTINCT FROM OLD.vehicle_id
    OR NEW.fuel_type      IS DISTINCT FROM OLD.fuel_type
    OR NEW.quantity       IS DISTINCT FROM OLD.quantity
    OR NEW.rate_per_litre IS DISTINCT FROM OLD.rate_per_litre
    OR NEW.amount         IS DISTINCT FROM OLD.amount
    OR NEW.supplied_at    IS DISTINCT FROM OLD.supplied_at
    OR NEW.cashier_id     IS DISTINCT FROM OLD.cashier_id THEN
        RAISE EXCEPTION 'Credit sale % is posted to the ledger and cannot be edited; raise an adjustment instead', OLD.txn_no;
    END IF;
    IF OLD.status = 'REVERSED' AND NEW.status <> 'REVERSED' THEN
        RAISE EXCEPTION 'Credit sale % has been reversed and cannot be reinstated', OLD.txn_no;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_freeze_credit_txn BEFORE UPDATE ON credit_transactions
    FOR EACH ROW EXECUTE FUNCTION fn_freeze_credit_txn();

-- The NO_RESPONSE job scans by age; the manager's queue filters by status.
CREATE INDEX ix_credit_txn_awaiting ON credit_transactions (created_at) WHERE approval_status = 'AWAITING_CUSTOMER_APPROVAL';
CREATE INDEX ix_adjustments_status ON adjustments (status, created_at);
