package com.petrolpump.credit.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * The only writer of ledger_entries. Posting locks the customer row so running balances stay
 * consistent when two cashiers post for the same customer at the same moment.
 */
@Service
public class LedgerService {

    public enum EntryType { CREDIT_SALE, PAYMENT, DEBIT_ADJUSTMENT, CREDIT_ADJUSTMENT }

    public record Posting(long customerId, EntryType type, BigDecimal debit, BigDecimal credit,
                          Long creditTransactionId, Long paymentId, Long adjustmentId, String narration, long actorId) {}

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside the caller's transaction so the posting commits or rolls back with the source document. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal post(Posting p) {
        jdbc.queryForObject("SELECT id FROM customers WHERE id = ? FOR UPDATE", Long.class, p.customerId());
        BigDecimal current = jdbc.queryForObject(
                "SELECT COALESCE(SUM(debit) - SUM(credit), 0) FROM ledger_entries WHERE customer_id = ?",
                BigDecimal.class, p.customerId());
        BigDecimal running = current.add(p.debit()).subtract(p.credit());
        jdbc.update("""
                INSERT INTO ledger_entries (customer_id, entry_type, debit, credit, running_balance,
                                            credit_transaction_id, payment_id, adjustment_id, narration, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, p.customerId(), p.type().name(), p.debit(), p.credit(), running,
                p.creditTransactionId(), p.paymentId(), p.adjustmentId(), p.narration(), p.actorId());
        return running;
    }
}
