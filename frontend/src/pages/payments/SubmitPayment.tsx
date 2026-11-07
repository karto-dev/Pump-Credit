import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, apiForm, formatINR, isoDay } from '../../api/client';
import type { CustomerDetail, Payment, PaymentMethod } from '../../api/types';
import { useAuth } from '../../auth';
import { ErrorBox, Field, Modal, nullIfBlank } from '../../components/ui';

export const METHODS: PaymentMethod[] = ['UPI', 'NEFT', 'IMPS', 'RTGS', 'CHEQUE', 'CASH'];

/** Customer tells the pump "I have paid". Nothing changes in the balance until the pump verifies it. */
export default function SubmitPayment() {
  const { user } = useAuth();
  const [account, setAccount] = useState<CustomerDetail | null>(null);
  const [pump, setPump] = useState<Record<string, string>>({});
  const [method, setMethod] = useState<PaymentMethod>('UPI');
  const [amount, setAmount] = useState('');
  const [date, setDate] = useState(isoDay());
  const [ref, setRef] = useState('');
  const [remarks, setRemarks] = useState('');
  const [proof, setProof] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [duplicate, setDuplicate] = useState<string | null>(null);
  const [saved, setSaved] = useState<Payment | null>(null);

  useEffect(() => {
    if (user?.customerId) api<CustomerDetail>(`/customers/${user.customerId}`).then(setAccount).catch(() => undefined);
    api<Record<string, string>>('/settings/public').then(setPump).catch(() => undefined);
  }, [user]);

  const submit = async (confirmDuplicate = false) => {
    setBusy(true); setError(null); setDuplicate(null);
    try {
      setSaved(await apiForm<Payment>('/payments/submissions', {
        payment: { paymentDate: date, amount: Number(amount), method, referenceNo: nullIfBlank(ref), remarks: nullIfBlank(remarks), confirmDuplicate },
      }, { proof }));
    } catch (e) {
      if (e instanceof ApiError && e.body.duplicate) setDuplicate(e.message);
      else setError(e instanceof Error ? e.message : 'Could not submit');
    } finally {
      setBusy(false);
    }
  };

  if (saved) {
    return (
      <div className="narrow">
        <div className="card center-card">
          <div className="big-icon ok-text"><i className="bi bi-receipt-cutoff" /></div>
          <h2 className="center-h">{saved.paymentNo} submitted</h2>
          <p><b>{formatINR(saved.amount)}</b> via {saved.method}</p>
          <p className="muted small">
            {saved.method === 'CASH'
              ? 'Hand the cash to the cashier. It is counted and verified before your balance goes down.'
              : 'The pump will check this against its bank statement. Your balance goes down once it is verified.'}
          </p>
          <div className="actions stack">
            <Link to="/payments" className="button">My payments</Link>
            <Link to="/" className="button">Dashboard</Link>
          </div>
        </div>
      </div>
    );
  }

  const needsRef = method !== 'CASH';
  return (
    <div className="narrow">
      <form className="card" onSubmit={(e: FormEvent) => { e.preventDefault(); submit(); }}>
        <h2>Report a payment</h2>
        {account && (
          <div className="amount-box">
            <span>Outstanding</span><b>{formatINR(account.confirmedOutstanding)}</b>
          </div>
        )}
        {(pump.PUMP_UPI_ID || pump.PUMP_BANK_DETAILS) && (
          <div className="pay-to small">
            <b>Pay {pump.PUMP_NAME ?? 'the pump'}</b>
            {pump.PUMP_UPI_ID && <div>UPI: <span className="mono">{pump.PUMP_UPI_ID}</span></div>}
            {pump.PUMP_BANK_DETAILS && <div className="pre">{pump.PUMP_BANK_DETAILS}</div>}
          </div>
        )}

        <div className="field-label">How did you pay? *</div>
        <div className="chips">
          {METHODS.map((m) => (
            <button type="button" key={m} className={`chip${method === m ? ' selected' : ''}`} onClick={() => setMethod(m)}>{m}</button>
          ))}
        </div>
        <div className="grid2">
          <Field label="Amount (₹) *">
            <input type="number" inputMode="decimal" min={1} step={0.01} value={amount} onChange={(e) => setAmount(e.target.value)} required />
          </Field>
          <Field label="Paid on *">
            <input type="date" value={date} max={isoDay()} onChange={(e) => setDate(e.target.value)} required />
          </Field>
        </div>
        {needsRef && (
          <Field label={method === 'CHEQUE' ? 'Cheque number *' : 'UTR / transaction reference *'}
                 hint={method === 'UPI' ? '12-digit UPI reference from your payment app' : undefined}>
            <input value={ref} onChange={(e) => setRef(e.target.value)} maxLength={60} required className="mono" />
          </Field>
        )}
        <div className="field-label">{method === 'CASH' ? 'Receipt photo (optional)' : 'Screenshot or slip (recommended)'}</div>
        <label className="photo-drop small-drop">
          <input type="file" accept="image/*,application/pdf" onChange={(e) => setProof(e.target.files?.[0] ?? null)} />
          <span>{proof ? <><i className="bi bi-file-earmark-check" /> {proof.name}</> : <><i className="bi bi-paperclip" /> Attach payment screenshot</>}</span>
        </label>
        <Field label="Note"><input value={remarks} onChange={(e) => setRemarks(e.target.value)} maxLength={500} /></Field>
        <p className="small muted">Your outstanding reduces only after the pump verifies this payment.</p>
        <ErrorBox error={error} />
        <button className="primary" disabled={busy || !amount || (needsRef && !ref.trim())}>
          {busy ? 'Submitting…' : `Submit ${formatINR(Number(amount) || 0)}`}
        </button>
      </form>

      {duplicate && (
        <Modal title="Possible duplicate" onClose={() => setDuplicate(null)}>
          <p className="notice">{duplicate}</p>
          <p className="muted">Submit only if this is a different payment.</p>
          <div className="actions">
            <button onClick={() => setDuplicate(null)}>Go back</button>
            <button className="primary auto danger" onClick={() => submit(true)} disabled={busy}>Submit anyway</button>
          </div>
        </Modal>
      )}
    </div>
  );
}
