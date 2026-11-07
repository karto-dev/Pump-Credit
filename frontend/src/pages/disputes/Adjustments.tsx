import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, formatDateTime, formatINR } from '../../api/client';
import type { Adjustment, CustomerRow } from '../../api/types';
import { useAuth } from '../../auth';
import ReasonModal from '../../components/ReasonModal';
import { AdjustmentBadge } from '../../components/status';
import { ErrorBox, Field, Modal, useAction } from '../../components/ui';

const TABS = [
  { key: 'PENDING_APPROVAL', label: 'Awaiting approval' },
  { key: 'APPROVED', label: 'Approved' },
  { key: 'REJECTED', label: 'Rejected' },
  { key: '', label: 'All' },
] as const;

type Dialog = { kind: 'approve' | 'reject'; adj: Adjustment } | null;

/** Manual debit / credit outside a dispute. Posts only after another approver approves it. */
function NewAdjustment({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [q, setQ] = useState('');
  const [matches, setMatches] = useState<CustomerRow[]>([]);
  const [customer, setCustomer] = useState<CustomerRow | null>(null);
  const [type, setType] = useState<'DEBIT' | 'CREDIT'>('DEBIT');
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const save = useAction();

  useEffect(() => {
    if (customer || q.trim().length < 2) { setMatches([]); return; }
    const t = setTimeout(() => api<CustomerRow[]>(`/customers?q=${encodeURIComponent(q)}`).then(setMatches).catch(() => undefined), 250);
    return () => clearTimeout(t);
  }, [q, customer]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.run(() => api('/adjustments', {
      method: 'POST', body: JSON.stringify({ customerId: customer!.id, adjType: type, amount: Number(amount), reason: reason.trim() }),
    }).then(onSaved));
  };

  return (
    <Modal title="New adjustment" onClose={onClose}>
      <form onSubmit={submit}>
        {!customer ? (
          <Field label="Customer *">
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name, phone or vehicle no." autoFocus />
            {matches.length > 0 && (
              <div className="suggestions">
                {matches.map((c) => (
                  <button type="button" key={c.id} onClick={() => setCustomer(c)}>
                    <b>{c.name}</b> <span className="muted small">{c.customerCode} · owes {formatINR(c.confirmedOutstanding)}</span>
                  </button>
                ))}
              </div>
            )}
          </Field>
        ) : (
          <div className="selected-customer">
            <div><b>{customer.name}</b> <span className="muted small">owes {formatINR(customer.confirmedOutstanding)}</span></div>
            <button type="button" className="link" onClick={() => setCustomer(null)}>Change</button>
          </div>
        )}
        <div className="segmented">
          <button type="button" className={type === 'DEBIT' ? 'selected' : ''} onClick={() => setType('DEBIT')}>Debit (+ outstanding)</button>
          <button type="button" className={type === 'CREDIT' ? 'selected' : ''} onClick={() => setType('CREDIT')}>Credit (− outstanding)</button>
        </div>
        <Field label="Amount (₹) *">
          <input type="number" inputMode="decimal" min={0.01} step={0.01} value={amount} onChange={(e) => setAmount(e.target.value)} required />
        </Field>
        <Field label="Reason *" hint="e.g. Late entry of fill on 02-11 bill 4411, bank charges, cheque bounce">
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} maxLength={1000} required />
        </Field>
        <p className="small muted">Another approver must approve it before it reaches the ledger.</p>
        <ErrorBox error={save.error} />
        <button className="primary" disabled={save.busy || !customer || !amount || !reason.trim()}>
          {save.busy ? 'Saving…' : `Raise ${type.toLowerCase()} of ${formatINR(Number(amount) || 0)}`}
        </button>
      </form>
    </Modal>
  );
}

/** Maker-checker queue: whoever raised an adjustment cannot approve it. */
export default function Adjustments() {
  const { user, can } = useAuth();
  const canApprove = can('ADJUSTMENT_APPROVE');
  const [tab, setTab] = useState<(typeof TABS)[number]['key']>('PENDING_APPROVAL');
  const [rows, setRows] = useState<Adjustment[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [creating, setCreating] = useState(false);
  const list = useAction();

  const load = useCallback(() => {
    list.run(() => api<Adjustment[]>(`/adjustments${tab ? `?status=${tab}` : ''}`).then((r) => { setRows(r); setLoaded(true); }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  useEffect(load, [load]);

  return (
    <>
      <div className="page-head">
        <h2>Adjustments</h2>
        {can('ADJUSTMENT_CREATE') && <button className="primary auto" onClick={() => setCreating(true)}><i className="bi bi-plus-lg" />New adjustment</button>}
      </div>
      {creating && <NewAdjustment onClose={() => setCreating(false)} onSaved={() => { setCreating(false); setTab('PENDING_APPROVAL'); load(); }} />}
      <div className="tabs">
        {TABS.map((t) => (
          <button key={t.key} className={tab === t.key ? 'selected' : ''} onClick={() => setTab(t.key)}>{t.label}</button>
        ))}
      </div>
      <ErrorBox error={list.error} />
      <div className="cards">
        {rows.map((a) => {
          const own = a.createdBy === user?.id;
          return (
            <div className="card indent-card" key={a.id}>
              <div className="indent-top">
                <div>
                  <b>{a.adjustmentNo}</b> <span className="small">{a.customerName}</span>
                  <div className="small muted">
                    {a.txnNo && <>for {a.txnNo} </>}
                    {a.disputeNo && <>· <Link to={`/disputes?q=${a.disputeNo}`}>{a.disputeNo}</Link></>}
                  </div>
                </div>
                <AdjustmentBadge status={a.status} />
              </div>
              <div className="indent-body">
                <b className={a.adjType === 'CREDIT' ? '' : 'bad-text'}>
                  {a.adjType === 'CREDIT' ? '− ' : '+ '}{formatINR(a.amount)}
                </b>
                <span className="small muted">{a.adjType === 'CREDIT' ? 'reduces outstanding' : 'increases outstanding'}</span>
              </div>
              <div className="small">{a.reason}</div>
              <div className="small muted">
                Raised by {a.createdByName} · {formatDateTime(a.createdAt)}
                {a.approvedAt && <> · {a.status === 'APPROVED' ? 'approved' : 'rejected'}{a.approvedByName ? ` by ${a.approvedByName}` : ''} {formatDateTime(a.approvedAt)}</>}
              </div>
              {a.approvalRemarks && <div className="small">Remarks: {a.approvalRemarks}</div>}
              {canApprove && a.status === 'PENDING_APPROVAL' && (
                <div className="card-actions">
                  {own
                    ? <span className="small muted">You raised this adjustment. Another approver must approve it.</span>
                    : <button className="primary auto" onClick={() => setDialog({ kind: 'approve', adj: a })}>Approve &amp; post</button>}
                  <button className="link" onClick={() => setDialog({ kind: 'reject', adj: a })}>Reject</button>
                </div>
              )}
            </div>
          );
        })}
        {loaded && !list.busy && rows.length === 0 && <div className="card empty">No adjustments here</div>}
      </div>

      {dialog && (
        <ReasonModal
          title={`${dialog.kind === 'approve' ? 'Approve' : 'Reject'} ${dialog.adj.adjustmentNo}`}
          confirmLabel={dialog.kind === 'approve' ? `Approve and post ${formatINR(dialog.adj.amount)}` : 'Reject adjustment'}
          danger={dialog.kind === 'reject'}
          onClose={() => setDialog(null)}
          onConfirm={(remarks) => api(`/adjustments/${dialog.adj.id}/${dialog.kind}`, {
            method: 'POST', body: JSON.stringify({ remarks }),
          }).then(load)}>
          <p className="small">{dialog.adj.customerName}: {dialog.adj.reason}</p>
          {dialog.kind === 'approve' && <p className="small muted">This posts a ledger entry that cannot be edited or deleted.</p>}
        </ReasonModal>
      )}
    </>
  );
}
