import { useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, ApiError, apiForm, formatDate, formatINR, isoDay } from '../../api/client';
import type { CustomerDetail, CustomerRow, Payment, PaymentMethod } from '../../api/types';
import { ErrorBox, Field, Modal, nullIfBlank } from '../../components/ui';
import { METHODS } from './SubmitPayment';

/** Cashier: money received at the counter, optionally against the customer's own open claim. */
export default function RecordPayment() {
  const [params] = useSearchParams();
  const [q, setQ] = useState('');
  const [matches, setMatches] = useState<CustomerRow[]>([]);
  const [customer, setCustomer] = useState<CustomerDetail | null>(null);
  const [claims, setClaims] = useState<Payment[]>([]);
  const [claim, setClaim] = useState<Payment | null>(null);
  const [method, setMethod] = useState<PaymentMethod>('CASH');
  const [amount, setAmount] = useState('');
  const [date, setDate] = useState(isoDay());
  const [ref, setRef] = useState('');
  const [bank, setBank] = useState('');
  const [remarks, setRemarks] = useState('');
  const [proof, setProof] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [duplicate, setDuplicate] = useState<string | null>(null);
  const [saved, setSaved] = useState<Payment | null>(null);

  useEffect(() => {
    if (customer || q.trim().length < 2) { setMatches([]); return; }
    const t = setTimeout(() => api<CustomerRow[]>(`/customers?q=${encodeURIComponent(q)}`).then(setMatches), 250);
    return () => clearTimeout(t);
  }, [q, customer]);

  // Deep link from a claim: /payments/record?payment=12
  useEffect(() => {
    const id = Number(params.get('payment'));
    if (!id) return;
    api<Payment>(`/payments/${id}`).then(async (p) => { await selectCustomer(p.customerId); applyClaim(p); })
      .catch((e) => setError(e.message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  const selectCustomer = async (id: number) => {
    const [c, open] = await Promise.all([
      api<CustomerDetail>(`/customers/${id}`),
      api<Payment[]>(`/payments?customerId=${id}&status=AWAITING_CASHIER_ENTRY,AWAITING_VERIFICATION,ADDITIONAL_PROOF_REQUIRED`),
    ]);
    setCustomer(c);
    setClaims(open.filter((p) => !p.cashierEntryId && p.submissionId));
    setMatches([]);
  };

  const applyClaim = (p: Payment | null) => {
    setClaim(p);
    if (p) { setMethod(p.method); setAmount(String(p.amount)); setDate(p.paymentDate); setRef(p.referenceNo ?? ''); }
  };

  const reset = () => {
    setCustomer(null); setQ(''); setClaims([]); setClaim(null); setMethod('CASH'); setAmount(''); setDate(isoDay());
    setRef(''); setBank(''); setRemarks(''); setProof(null); setSaved(null); setError(null);
  };

  const submit = async (confirmDuplicate = false) => {
    if (!customer) return;
    setBusy(true); setError(null); setDuplicate(null);
    try {
      setSaved(await apiForm<Payment>('/payments/cashier-entries', {
        entry: {
          customerId: customer.id, paymentId: claim?.id ?? null, paymentDate: date, amount: Number(amount), method,
          referenceNo: nullIfBlank(ref), chequeBank: method === 'CHEQUE' ? nullIfBlank(bank) : null,
          remarks: nullIfBlank(remarks), confirmDuplicate,
        },
      }, { proof }));
    } catch (e) {
      if (e instanceof ApiError && e.body.duplicate) setDuplicate(e.message);
      else setError(e instanceof Error ? e.message : 'Could not save');
    } finally {
      setBusy(false);
    }
  };

  if (saved) {
    return (
      <div className="narrow">
        <div className="card center-card">
          <div className="big-icon ok-text"><i className="bi bi-check-circle-fill" /></div>
          <h2 className="center-h">{saved.entryNo ?? saved.paymentNo} recorded</h2>
          <p><b>{formatINR(saved.amount)}</b> {saved.method} · {saved.customerName}</p>
          <p className="muted small">Sent to the manager for verification. The customer's balance changes once it is verified.</p>
          <div className="actions stack">
            <button className="primary" onClick={reset}>Next payment</button>
            <Link to="/payments" className="button">Payments</Link>
          </div>
        </div>
      </div>
    );
  }

  const needsRef = method !== 'CASH';
  return (
    <div className="narrow">
      <form className="card" onSubmit={(e: FormEvent) => { e.preventDefault(); submit(); }}>
        <h2>Record payment received</h2>
        {!customer ? (
          <Field label="Customer *">
            <input placeholder="Type name, phone or vehicle no." value={q} onChange={(e) => setQ(e.target.value)} autoFocus />
            {matches.length > 0 && (
              <div className="suggestions">
                {matches.map((c) => (
                  <button type="button" key={c.id} onClick={() => selectCustomer(c.id)}>
                    <b>{c.name}</b> <span className="muted small">{c.customerCode} · {c.phone} · owes {formatINR(c.confirmedOutstanding)}</span>
                  </button>
                ))}
              </div>
            )}
          </Field>
        ) : (
          <div className="selected-customer">
            <div>
              <b>{customer.name}</b> <span className="muted small">{customer.customerCode}</span>
              <div className="small muted">Outstanding {formatINR(customer.confirmedOutstanding)}</div>
            </div>
            <button type="button" className="link" onClick={reset}>Change</button>
          </div>
        )}

        {customer && <>
          {claims.length > 0 && (
            <Field label="Customer's claim" hint="Pick the claim this money belongs to so it is not counted twice">
              <select value={claim?.id ?? ''} onChange={(e) => applyClaim(claims.find((p) => p.id === Number(e.target.value)) ?? null)}>
                <option value="">Not against a claim</option>
                {claims.map((p) => (
                  <option key={p.id} value={p.id}>{p.paymentNo} · {p.method} · {formatINR(p.amount)} · {formatDate(p.paymentDate)}</option>
                ))}
              </select>
            </Field>
          )}
          <div className="field-label">Method *</div>
          <div className="chips">
            {METHODS.map((m) => (
              <button type="button" key={m} className={`chip${method === m ? ' selected' : ''}`} onClick={() => setMethod(m)}>{m}</button>
            ))}
          </div>
          <div className="grid2">
            <Field label="Amount received (₹) *">
              <input type="number" inputMode="decimal" min={1} step={0.01} value={amount} onChange={(e) => setAmount(e.target.value)} required />
            </Field>
            <Field label="Received on *">
              <input type="date" value={date} max={isoDay()} onChange={(e) => setDate(e.target.value)} required />
            </Field>
          </div>
          {needsRef && (
            <div className="grid2">
              <Field label={method === 'CHEQUE' ? 'Cheque number *' : 'UTR / reference *'}>
                <input value={ref} onChange={(e) => setRef(e.target.value)} maxLength={60} required className="mono" />
              </Field>
              {method === 'CHEQUE' && <Field label="Bank"><input value={bank} onChange={(e) => setBank(e.target.value)} maxLength={80} /></Field>}
            </div>
          )}
          <div className="field-label">{needsRef && !claim ? 'Screenshot / slip *' : 'Proof (optional)'}</div>
          <label className="photo-drop small-drop">
            <input type="file" accept="image/*,application/pdf" capture="environment" onChange={(e) => setProof(e.target.files?.[0] ?? null)} />
            <span>{proof ? <><i className="bi bi-file-earmark-check" /> {proof.name}</> : <><i className="bi bi-camera" /> Photo of slip, cheque or screenshot</>}</span>
          </label>
          <Field label="Remarks"><input value={remarks} onChange={(e) => setRemarks(e.target.value)} maxLength={500} /></Field>
          <ErrorBox error={error} />
          <button className="primary" disabled={busy || !amount || (needsRef && !ref.trim()) || (needsRef && !claim && !proof)}>
            {busy ? 'Saving…' : `Record ${formatINR(Number(amount) || 0)}`}
          </button>
        </>}
      </form>

      {duplicate && (
        <Modal title="Possible duplicate" onClose={() => setDuplicate(null)}>
          <p className="notice">{duplicate}</p>
          <p className="muted">Record only if this really is separate money. The override is recorded in the audit log.</p>
          <div className="actions">
            <button onClick={() => setDuplicate(null)}>Go back</button>
            <button className="primary auto danger" onClick={() => submit(true)} disabled={busy}>Record anyway</button>
          </div>
        </Modal>
      )}
    </div>
  );
}
