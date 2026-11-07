import { useEffect, useState } from 'react';
import { api, apiBlobUrl, apiForm, formatDate, formatDateTime, formatINR } from '../api/client';
import type { Payment, PaymentHistory, PaymentProof } from '../api/types';
import { useAuth } from '../auth';
import { PAYMENT_LABELS, PaymentBadge } from './status';
import { ErrorBox, Field, Modal, useAction } from './ui';

const OPEN = ['AWAITING_CASHIER_ENTRY', 'AWAITING_VERIFICATION', 'ADDITIONAL_PROOF_REQUIRED'];
const VERIFIABLE = ['AWAITING_VERIFICATION', 'ADDITIONAL_PROOF_REQUIRED'];

type Action = 'verify' | 'reject' | 'request-proof' | 'mark-duplicate' | 'cancel' | 'proof' | null;

/**
 * One payment: what the customer claimed, what the cashier received, every proof file and every
 * verification step. Verifiers act here; the customer can withdraw a claim or answer a proof request.
 */
export default function PaymentViewer({ payment, onClose, onChanged }: {
  payment: Payment; onClose: () => void; onChanged?: (p: Payment) => void;
}) {
  const { user, can } = useAuth();
  const [current, setCurrent] = useState(payment);
  const [proofs, setProofs] = useState<PaymentProof[]>([]);
  const [shown, setShown] = useState<{ id: number; url: string; type: string } | null>(null);
  const [history, setHistory] = useState<PaymentHistory[]>([]);
  const [action, setAction] = useState<Action>(null);
  const [remarks, setRemarks] = useState('');
  const [evidence, setEvidence] = useState('');
  const [verifiedAmount, setVerifiedAmount] = useState(String(payment.amount));
  const [dupOf, setDupOf] = useState(payment.duplicateOfNo ?? '');
  const [file, setFile] = useState<File | null>(null);
  const act = useAction();

  useEffect(() => {
    api<PaymentProof[]>(`/payments/${current.id}/proofs`).then((p) => { setProofs(p); if (p.length) show(p[p.length - 1].id); })
      .catch(() => setProofs([]));
    api<PaymentHistory[]>(`/payments/${current.id}/history`).then(setHistory).catch(() => setHistory([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current.id, current.status, current.proofCount]);

  useEffect(() => () => { if (shown) URL.revokeObjectURL(shown.url); }, [shown]);

  const show = (proofId: number) => apiBlobUrl(`/payments/${current.id}/proofs/${proofId}`)
    .then((f) => setShown({ id: proofId, ...f })).catch(() => undefined);

  const isCustomer = user?.role === 'CUSTOMER';
  const open = OPEN.includes(current.status);
  const ownEntry = current.recordedBy === user?.id;
  const canVerify = can('PAYMENT_VERIFY') && !isCustomer && VERIFIABLE.includes(current.status);
  const canCancel = isCustomer && can('PAYMENT_SUBMIT') && open && !current.cashierEntryId;
  const canAddProof = open && (isCustomer ? can('PAYMENT_SUBMIT') : ownEntry || can('PAYMENT_RECORD'));

  const done = (p: Payment) => { setCurrent(p); setAction(null); setRemarks(''); setFile(null); onChanged?.(p); };

  const submit = () => act.run(async () => {
    if (action === 'proof') {
      return done(await apiForm<Payment>(`/payments/${current.id}/proofs${remarks.trim() ? `?remarks=${encodeURIComponent(remarks.trim())}` : ''}`, {}, { proof: file }));
    }
    const body: Record<string, unknown> = { remarks: remarks.trim() };
    if (action === 'verify') { body.verifiedAmount = Number(verifiedAmount); body.evidenceChecked = evidence.trim() || null; }
    if (action === 'mark-duplicate') body.duplicateOfNo = dupOf.trim();
    done(await api<Payment>(`/payments/${current.id}/${action}`, { method: 'POST', body: JSON.stringify(body) }));
  });

  const short = current.status === 'VERIFIED' && current.verifiedAmount != null && Number(current.verifiedAmount) < Number(current.amount);

  return (
    <Modal title={`Payment ${current.paymentNo}`} onClose={onClose} wide>
      <div className="bill-layout">
        <div className="bill-image">
          {proofs.length === 0 && <div className="card empty">No proof attached{current.method === 'CASH' ? ' (cash)' : ''}</div>}
          {proofs.length > 1 && (
            <div className="chips">
              {proofs.map((p, i) => (
                <button key={p.id} type="button" className={`chip${shown?.id === p.id ? ' selected' : ''}`} onClick={() => show(p.id)}>
                  Proof {i + 1}
                </button>
              ))}
            </div>
          )}
          {shown && (shown.type === 'application/pdf'
            ? <iframe src={shown.url} title="Payment proof" />
            : <a href={shown.url} target="_blank" rel="noreferrer"><img src={shown.url} alt="Payment proof" /></a>)}
          {shown && (() => {
            const p = proofs.find((x) => x.id === shown.id);
            return p && <div className="small muted">Uploaded by {p.uploadedByName} · {formatDateTime(p.uploadedAt)}</div>;
          })()}
        </div>
        <div>
          <dl className="kv">
            <dt>Customer</dt><dd>{current.customerName} <span className="muted small">{current.customerCode}</span></dd>
            <dt>Amount</dt><dd><b>{formatINR(current.amount)}</b></dd>
            {current.verifiedAmount != null && <><dt>Verified</dt><dd><b className="ok-text">{formatINR(current.verifiedAmount)}</b>{short && <span className="warn-text small"> · short credited</span>}</dd></>}
            <dt>Method</dt><dd>{current.method}{current.chequeBank ? ` · ${current.chequeBank}` : ''}</dd>
            <dt>{current.method === 'CHEQUE' ? 'Cheque no.' : 'UTR / ref'}</dt><dd className="mono">{current.referenceNo ?? '—'}</dd>
            <dt>Paid on</dt><dd>{formatDate(current.paymentDate)}</dd>
            <dt>Status</dt><dd><PaymentBadge status={current.status} />{current.duplicateFlag && <span className="badge warn">possible duplicate{current.duplicateOfNo ? ` of ${current.duplicateOfNo}` : ''}</span>}</dd>
            {current.submissionNo && <><dt>Customer claim</dt><dd>{current.submissionNo} · {formatINR(current.claimedAmount ?? 0)} · {current.submittedByName} {current.submittedAt && formatDateTime(current.submittedAt)}</dd></>}
            {current.submissionRemarks && <><dt>Customer note</dt><dd>{current.submissionRemarks}</dd></>}
            {current.entryNo && <><dt>Cashier entry</dt><dd>{current.entryNo} · {formatINR(current.receivedAmount ?? 0)} · {current.recordedByName} {current.recordedAt && formatDateTime(current.recordedAt)}</dd></>}
            {current.entryRemarks && <><dt>Cashier note</dt><dd>{current.entryRemarks}</dd></>}
            {current.verifiedByName && <><dt>Verified by</dt><dd>{current.verifiedByName} · {current.verifiedAt && formatDateTime(current.verifiedAt)}</dd></>}
          </dl>
          {current.submissionNo && current.entryNo && Number(current.claimedAmount) !== Number(current.receivedAmount) && (
            <p className="notice small">Customer claimed {formatINR(current.claimedAmount ?? 0)} but the cashier recorded {formatINR(current.receivedAmount ?? 0)}.</p>
          )}
          {current.status === 'ADDITIONAL_PROOF_REQUIRED' && current.lastRemarks && (
            <p className="notice small">More proof requested: {current.lastRemarks}</p>
          )}
          {current.method === 'CASH' && !current.cashierEntryId && open && (
            <p className="small muted">Cash is verified only after a cashier counts it and records it against this claim.</p>
          )}

          {!action && (
            <div className="card-actions">
              {canVerify && (ownEntry
                ? <span className="small muted">You recorded this payment, so someone else must verify it.</span>
                : <button className="primary auto" onClick={() => setAction('verify')}
                          disabled={current.method === 'CASH' && !current.cashierEntryId}><i className="bi bi-patch-check" />Verify &amp; post</button>)}
              {canVerify && current.status === 'AWAITING_VERIFICATION' && <button onClick={() => setAction('request-proof')}>Ask for proof</button>}
              {can('PAYMENT_VERIFY') && !isCustomer && current.status === 'AWAITING_CASHIER_ENTRY' && <button onClick={() => setAction('request-proof')}>Ask for proof</button>}
              {can('PAYMENT_VERIFY') && !isCustomer && open && <button onClick={() => setAction('mark-duplicate')}>Duplicate</button>}
              {can('PAYMENT_VERIFY') && !isCustomer && open && <button className="danger-outline" onClick={() => setAction('reject')}>Reject</button>}
              {canAddProof && <button onClick={() => setAction('proof')}>{current.status === 'ADDITIONAL_PROOF_REQUIRED' ? 'Upload requested proof' : 'Add proof'}</button>}
              {canCancel && <button className="link" onClick={() => setAction('cancel')}>Withdraw claim</button>}
            </div>
          )}

          {action && (
            <form className="approval-box" onSubmit={(e) => { e.preventDefault(); submit(); }}>
              {action === 'verify' && <>
                <Field label="Amount received in bank / cash (₹) *" hint="Lower than the payment amount only if less actually arrived">
                  <input type="number" inputMode="decimal" min={0.01} max={current.amount} step={0.01} value={verifiedAmount}
                         onChange={(e) => setVerifiedAmount(e.target.value)} required />
                </Field>
                <Field label="Evidence checked" hint="e.g. HDFC statement 05-11-2026, line 14">
                  <input value={evidence} onChange={(e) => setEvidence(e.target.value)} maxLength={255} />
                </Field>
              </>}
              {action === 'mark-duplicate' && (
                <Field label="Duplicate of payment no. *"><input value={dupOf} onChange={(e) => setDupOf(e.target.value)} placeholder="PAY-0000001" required /></Field>
              )}
              {action === 'proof' && (
                <label className="photo-drop small-drop">
                  <input type="file" accept="image/*,application/pdf" onChange={(e) => setFile(e.target.files?.[0] ?? null)} required />
                  <span>{file ? <><i className="bi bi-file-earmark-check" /> {file.name}</> : <><i className="bi bi-paperclip" /> Choose screenshot, slip or PDF</>}</span>
                </label>
              )}
              <Field label={action === 'proof' ? 'Note' : action === 'request-proof' ? 'What proof is needed? *' : 'Remarks *'}
                     hint={action === 'verify' ? 'Posts to the ledger immediately and cannot be undone' : 'Recorded in the audit log'}>
                <textarea value={remarks} onChange={(e) => setRemarks(e.target.value)} rows={2} maxLength={1000} required={action !== 'proof'} />
              </Field>
              <ErrorBox error={act.error} />
              <div className="card-actions">
                <button className={`primary auto${['reject', 'cancel'].includes(action) ? ' danger' : ''}`}
                        disabled={act.busy || (action !== 'proof' && !remarks.trim()) || (action === 'proof' && !file)}>
                  {act.busy ? 'Saving…' : {
                    verify: `Verify ${formatINR(Number(verifiedAmount) || 0)}`, reject: 'Reject payment', 'request-proof': 'Send request',
                    'mark-duplicate': 'Mark duplicate', cancel: 'Withdraw claim', proof: 'Upload',
                  }[action]}
                </button>
                <button type="button" className="link" onClick={() => { setAction(null); act.setError(null); }}>Back</button>
              </div>
            </form>
          )}

          {history.length > 0 && (
            <div className="history">
              <h4>History</h4>
              <ol>
                {history.map((h) => (
                  <li key={h.id}>
                    <b>{PAYMENT_LABELS[h.toStatus]}</b> · {h.actedByName} · <span className="muted">{formatDateTime(h.actedAt)}</span>
                    {h.verifiedAmount != null && <> · {formatINR(h.verifiedAmount)}</>}
                    <div className="small muted">{h.remarks}{h.evidenceChecked ? ` · Evidence: ${h.evidenceChecked}` : ''}</div>
                  </li>
                ))}
              </ol>
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
}
