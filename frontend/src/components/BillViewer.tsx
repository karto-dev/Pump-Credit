import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, apiBlobUrl, formatDateTime, formatINR, formatQty } from '../api/client';
import type { ApprovalStep, Sale } from '../api/types';
import { useAuth } from '../auth';
import { APPROVAL_LABELS, ApprovalBadge } from './status';
import { ErrorBox, Field, Modal, useAction } from './ui';

const ANSWERABLE = ['AWAITING_CUSTOMER_APPROVAL', 'NO_RESPONSE'];

/**
 * Bill photo + the figures the customer needs to check against it. The billed customer can approve
 * or reject here; everyone who can see the sale gets the approval history.
 */
export default function BillViewer({ sale, onClose, onChanged }: {
  sale: Sale; onClose: () => void; onChanged?: (s: Sale) => void;
}) {
  const { user, can } = useAuth();
  const [current, setCurrent] = useState(sale);
  const [file, setFile] = useState<{ url: string; type: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<ApprovalStep[]>([]);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const act = useAction();

  useEffect(() => {
    let url: string | null = null;
    apiBlobUrl(`/credit-sales/${sale.id}/bill`)
      .then((f) => { url = f.url; setFile(f); })
      .catch((e) => setError(e.message));
    return () => { if (url) URL.revokeObjectURL(url); };
  }, [sale.id]);

  useEffect(() => {
    api<ApprovalStep[]>(`/credit-sales/${current.id}/approvals`).then(setHistory).catch(() => setHistory([]));
  }, [current.id, current.approvalStatus]);

  const canAnswer = user?.role === 'CUSTOMER' && can('BILL_APPROVE_OWN') && current.status === 'POSTED'
    && ANSWERABLE.includes(current.approvalStatus);

  const done = (s: Sale) => { setCurrent(s); setRejecting(false); onChanged?.(s); };
  const approve = () => act.run(() => api<Sale>(`/credit-sales/${current.id}/approve`, { method: 'POST', body: '{}' }).then(done));
  const reject = () => act.run(() => api<{ sale: Sale }>(`/credit-sales/${current.id}/reject`, {
    method: 'POST', body: JSON.stringify({ reason: reason.trim() }),
  }).then((r) => done(r.sale)));

  return (
    <Modal title={`Bill ${current.txnNo}`} onClose={onClose} wide>
      <div className="bill-layout">
        <div className="bill-image">
          <ErrorBox error={error} />
          {!file && !error && <div className="muted">Loading photo…</div>}
          {file && (file.type === 'application/pdf'
            ? <iframe src={file.url} title="Bill PDF" />
            : <a href={file.url} target="_blank" rel="noreferrer"><img src={file.url} alt={`Bill for ${current.txnNo}`} /></a>)}
        </div>
        <div>
          <dl className="kv">
            <dt>Customer</dt><dd>{current.customerName}</dd>
            <dt>Vehicle</dt><dd className="mono">{current.vehicleNumber}</dd>
            <dt>Fuel</dt><dd>{current.fuelType}</dd>
            <dt>Quantity</dt><dd>{formatQty(current.quantity)}</dd>
            <dt>Rate</dt><dd>{formatINR(current.ratePerLitre)} / L</dd>
            <dt>Amount</dt><dd><b>{formatINR(current.amount)}</b>{current.status === 'REVERSED' && <span className="bad-text"> · reversed</span>}</dd>
            <dt>Supplied</dt><dd>{formatDateTime(current.suppliedAt)}</dd>
            <dt>Bill no.</dt><dd>{current.physicalBillNo ?? '—'}</dd>
            <dt>Indent</dt><dd>{current.indentNo ?? 'Without indent'}</dd>
            <dt>Cashier</dt><dd>{current.cashierName}</dd>
            <dt>Approval</dt><dd><ApprovalBadge status={current.approvalStatus} /></dd>
            {current.disputeNo && <><dt>Dispute</dt><dd><Link to={`/disputes?q=${current.disputeNo}`}>{current.disputeNo}</Link></dd></>}
            {current.remarks && <><dt>Remarks</dt><dd>{current.remarks}</dd></>}
          </dl>

          {canAnswer && !rejecting && (
            <div className="approval-box">
              <p className="small muted">Check the photo against the quantity, rate and amount. Your answer is optional: the sale is already in your account either way.</p>
              <div className="card-actions">
                <button className="primary auto" onClick={approve} disabled={act.busy}><i className="bi bi-check2-circle" />Approve bill</button>
                <button className="danger-outline" onClick={() => setRejecting(true)} disabled={act.busy}><i className="bi bi-x-circle" />Reject / dispute</button>
              </div>
            </div>
          )}
          {canAnswer && rejecting && (
            <form className="approval-box" onSubmit={(e) => { e.preventDefault(); reject(); }}>
              <Field label="What is wrong with this bill? *" hint="The manager will investigate">
                <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={500} required autoFocus />
              </Field>
              <div className="card-actions">
                <button className="primary danger auto" disabled={act.busy || !reason.trim()}>{act.busy ? 'Sending…' : 'Reject bill'}</button>
                <button type="button" className="link" onClick={() => setRejecting(false)}>Back</button>
              </div>
            </form>
          )}
          <ErrorBox error={act.error} />

          {history.length > 0 && (
            <div className="history">
              <h4>Approval history</h4>
              <ol>
                {history.map((h) => (
                  <li key={h.id}>
                    <b>{APPROVAL_LABELS[h.toStatus]}</b> · {h.actedByName} · <span className="muted">{formatDateTime(h.actedAt)}</span>
                    {h.reason && <div className="small muted">{h.reason}</div>}
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
