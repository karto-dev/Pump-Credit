import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, formatDateTime, formatINR, formatQty } from '../../api/client';
import type { Dispute, Resolution, Sale } from '../../api/types';
import { useAuth } from '../../auth';
import BillViewer from '../../components/BillViewer';
import { AdjustmentBadge, ApprovalBadge, DisputeBadge, RESOLUTION_LABELS } from '../../components/status';
import { ErrorBox, Field, Modal, useAction } from '../../components/ui';

const TABS = [
  { key: 'active', label: 'Needs action', statuses: 'OPEN,UNDER_INVESTIGATION' },
  { key: 'resolved', label: 'Resolved', statuses: 'RESOLVED' },
  { key: 'all', label: 'All', statuses: '' },
] as const;

export default function Disputes() {
  const { user, can } = useAuth();
  const isCustomer = user?.role === 'CUSTOMER';
  const canResolve = can('DISPUTE_RESOLVE');
  const [params] = useSearchParams();
  const [tab, setTab] = useState<(typeof TABS)[number]['key']>(params.get('q') || isCustomer ? 'all' : 'active');
  const [q, setQ] = useState(params.get('q') ?? '');
  const [rows, setRows] = useState<Dispute[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [resolving, setResolving] = useState<Dispute | null>(null);
  const [viewing, setViewing] = useState<Sale | null>(null);
  const list = useAction();
  const act = useAction();

  const load = useCallback(() => {
    const p = new URLSearchParams();
    const statuses = TABS.find((t) => t.key === tab)!.statuses;
    if (statuses) p.set('status', statuses);
    if (q) p.set('q', q);
    list.run(() => api<Dispute[]>(`/disputes?${p}`).then((r) => { setRows(r); setLoaded(true); }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, q]);

  useEffect(() => {
    const t = setTimeout(load, 250);
    return () => clearTimeout(t);
  }, [load]);

  const investigate = (d: Dispute) =>
    act.run(() => api(`/disputes/${d.id}/investigate`, { method: 'POST', body: '{}' })).then(load);
  const openBill = (d: Dispute) => act.run(() => api<Sale>(`/credit-sales/${d.saleId}`).then(setViewing));

  return (
    <>
      <div className="page-head">
        <h2>{isCustomer ? 'My disputes' : 'Disputed bills'}</h2>
      </div>
      <div className="tabs">
        {TABS.map((t) => (
          <button key={t.key} className={tab === t.key ? 'selected' : ''} onClick={() => setTab(t.key)}>{t.label}</button>
        ))}
      </div>
      {!isCustomer && (
        <div className="filters">
          <input placeholder="Search customer, dispute, bill or vehicle" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
      )}
      <ErrorBox error={list.error ?? act.error} />

      <div className="cards">
        {rows.map((d) => (
          <div className="card indent-card" key={d.id}>
            <div className="indent-top">
              <div>
                <b>{d.disputeNo}</b> <span className="small muted">on {d.txnNo}</span>
                {!isCustomer && <div className="small">{d.customerName}</div>}
              </div>
              <DisputeBadge status={d.status} />
            </div>
            <div className="indent-body">
              <span className="mono vehicle">{d.vehicleNumber}</span>
              <span className={`fuel ${d.fuelType.toLowerCase()}`}>{d.fuelType}</span>
              <span>{formatQty(d.quantity)} @ {formatINR(d.ratePerLitre)}</span>
              <b>{formatINR(d.amount)}</b>
              {d.saleStatus === 'REVERSED' && <span className="small bad-text">reversed</span>}
            </div>
            <div className="quote">“{d.reason}”</div>
            <div className="small muted">
              Raised by {d.raisedByName} · {formatDateTime(d.raisedAt)} · supplied {formatDateTime(d.suppliedAt)} by {d.cashierName}
              {d.assignedToName && <> · handled by <b>{d.assignedToName}</b></>}
            </div>
            {d.resolutionType && (
              <div className="resolution">
                <b>{RESOLUTION_LABELS[d.resolutionType]}</b>
                {d.adjustmentNo && <> · {d.adjustmentNo} {formatINR(d.adjustmentAmount!)} <AdjustmentBadge status={d.adjustmentStatus!} /></>}
                <div className="small">{d.resolutionNotes}</div>
                {d.resolvedAt && <div className="small muted">Resolved {formatDateTime(d.resolvedAt)}{d.resolvedByName ? ` by ${d.resolvedByName}` : ''}</div>}
              </div>
            )}
            <div className="card-actions">
              <ApprovalBadge status={d.approvalStatus} />
              <button className="link" onClick={() => openBill(d)} disabled={act.busy}>View bill</button>
              {canResolve && d.status === 'OPEN' && (
                <button onClick={() => investigate(d)} disabled={act.busy}>Take up</button>
              )}
              {canResolve && d.status !== 'RESOLVED' && !d.adjustmentId && (
                <button className="primary auto" onClick={() => setResolving(d)}>Resolve</button>
              )}
              {d.adjustmentStatus === 'PENDING_APPROVAL' && can('ADJUSTMENT_APPROVE') && (
                <Link to="/adjustments" className="small">Review adjustment →</Link>
              )}
            </div>
          </div>
        ))}
        {loaded && !list.busy && rows.length === 0 && (
          <div className="card empty">{isCustomer ? 'You have not disputed any bills' : 'No disputes here'}</div>
        )}
      </div>

      {resolving && <ResolveDialog dispute={resolving} onClose={() => setResolving(null)} onDone={load} />}
      {viewing && <BillViewer sale={viewing} onClose={() => setViewing(null)} />}
    </>
  );
}

function ResolveDialog({ dispute, onClose, onDone }: { dispute: Dispute; onClose: () => void; onDone: () => void }) {
  const [resolution, setResolution] = useState<Resolution>('BILL_UPHELD');
  const [amount, setAmount] = useState('');
  const [notes, setNotes] = useState('');
  const { busy, error, run } = useAction();

  const options: { key: Resolution; title: string; help: string }[] = [
    { key: 'BILL_UPHELD', title: 'Uphold the bill', help: 'The bill is correct. Nothing changes in the ledger.' },
    { key: 'ADJUSTED', title: 'Credit part of the amount', help: 'For a wrong rate or quantity. Needs approval by an admin.' },
    { key: 'REVERSED', title: `Reverse the whole bill (${formatINR(dispute.amount)})`, help: 'Fuel was not supplied. Needs approval by an admin.' },
  ];

  const submit = async () => {
    const ok = await run(() => api(`/disputes/${dispute.id}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ resolution, notes: notes.trim(), amount: resolution === 'ADJUSTED' ? Number(amount) : null }),
    }));
    if (ok !== undefined) { onDone(); onClose(); }
  };

  return (
    <Modal title={`Resolve ${dispute.disputeNo}`} onClose={onClose}>
      <form onSubmit={(e) => { e.preventDefault(); submit(); }}>
        <div className="quote" style={{ marginBottom: 12 }}>“{dispute.reason}”</div>
        <div className="radio-list">
          {options.map((o) => (
            <label key={o.key} className={resolution === o.key ? 'selected' : ''}>
              <input type="radio" name="resolution" checked={resolution === o.key} onChange={() => setResolution(o.key)} />
              <span><b>{o.title}</b><br /><span className="small muted">{o.help}</span></span>
            </label>
          ))}
        </div>
        {resolution === 'ADJUSTED' && (
          <Field label="Amount to credit back (₹) *" hint={`Less than the bill amount of ${formatINR(dispute.amount)}`}>
            <input type="number" min={0.01} max={Number(dispute.amount) - 0.01} step="0.01" value={amount}
              onChange={(e) => setAmount(e.target.value)} required />
          </Field>
        )}
        <Field label="Findings *" hint="Shown to the customer and recorded in the audit log">
          <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={3} maxLength={900} required />
        </Field>
        <ErrorBox error={error} />
        <button className="primary" disabled={busy || !notes.trim() || (resolution === 'ADJUSTED' && !amount)}>
          {busy ? 'Saving…' : resolution === 'BILL_UPHELD' ? 'Resolve dispute' : 'Send adjustment for approval'}
        </button>
      </form>
    </Modal>
  );
}
