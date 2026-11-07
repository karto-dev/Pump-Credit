import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api, formatDateTime, formatINR, formatQty } from '../../api/client';
import type { Indent } from '../../api/types';
import { useAuth } from '../../auth';
import ReasonModal from '../../components/ReasonModal';
import { IndentBadge } from '../../components/status';
import { ErrorBox, Field, useAction } from '../../components/ui';

const TABS = [
  { key: 'open', label: 'Open', statuses: 'PENDING,ACCEPTED' },
  { key: 'hold', label: 'On hold', statuses: 'ON_HOLD_CREDIT_LIMIT' },
  { key: 'done', label: 'Supplied', statuses: 'COMPLETED' },
  { key: 'closed', label: 'Cancelled / rejected', statuses: 'CANCELLED,REJECTED,EXPIRED' },
  { key: 'all', label: 'All', statuses: '' },
] as const;

type Dialog = { kind: 'reject' | 'cancel' | 'authorise'; indent: Indent } | null;

export default function Indents() {
  const { user, can } = useAuth();
  const navigate = useNavigate();
  const isCustomer = user?.role === 'CUSTOMER';
  const canProcess = can('INDENT_PROCESS');
  const canAuthorise = user?.role === 'ADMIN' && can('CREDIT_LIMIT_EXCEPTION');
  const [tab, setTab] = useState<(typeof TABS)[number]['key']>(isCustomer ? 'all' : 'open');
  const [q, setQ] = useState('');
  const [rows, setRows] = useState<Indent[]>([]);
  const [loaded, setLoaded] = useState(false); // avoid flashing "empty" before the first response
  const [dialog, setDialog] = useState<Dialog>(null);
  const list = useAction();
  const act = useAction();

  const load = useCallback(() => {
    const p = new URLSearchParams();
    const statuses = TABS.find((t) => t.key === tab)!.statuses;
    if (statuses) p.set('status', statuses);
    if (q) p.set('q', q);
    list.run(() => api<Indent[]>(`/indents?${p}`).then((r) => { setRows(r); setLoaded(true); }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, q]);

  useEffect(() => {
    const t = setTimeout(load, 250);
    return () => clearTimeout(t);
  }, [load]);

  // Cashier queue refreshes itself so new indents show up without a reload.
  useEffect(() => {
    if (isCustomer) return;
    const t = setInterval(load, 30000);
    return () => clearInterval(t);
  }, [load, isCustomer]);

  const accept = (i: Indent) => act.run(() => api(`/indents/${i.id}/accept`, { method: 'POST' })).then(load);

  return (
    <>
      <div className="page-head">
        <h2>{isCustomer ? 'My indents' : 'Indents'}</h2>
        {can('INDENT_CREATE') && <Link to="/indents/new" className="button primary-link"><i className="bi bi-plus-lg" />New indent</Link>}
      </div>
      <div className="tabs">
        {TABS.filter((t) => !isCustomer || t.key !== 'hold').map((t) => (
          <button key={t.key} className={tab === t.key ? 'selected' : ''} onClick={() => setTab(t.key)}>{t.label}</button>
        ))}
      </div>
      {!isCustomer && (
        <div className="filters">
          <input placeholder="Search customer, indent no. or vehicle" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
      )}
      <ErrorBox error={list.error ?? act.error} />

      <div className="cards">
        {rows.map((i) => (
          <div className="card indent-card" key={i.id}>
            <div className="indent-top">
              <div>
                <div className="mono vehicle">{i.vehicleNumber}</div>
                {!isCustomer && <div className="small">{i.customerName}</div>}
              </div>
              <IndentBadge status={i.status} />
            </div>
            <div className="indent-body">
              <span className={`fuel ${i.fuelType.toLowerCase()}`}>{i.fuelType}</span>
              <b>{i.fullTank ? 'Full tank' : i.requestedQuantity ? formatQty(i.requestedQuantity) : formatINR(i.requestedAmount!)}</b>
              {i.status === 'COMPLETED' && i.suppliedQuantity != null && (
                <span className="small muted"> → supplied {formatQty(i.suppliedQuantity)} · {formatINR(i.suppliedAmount!)}</span>
              )}
            </div>
            {i.instructions && <div className="small">“{i.instructions}”</div>}
            {i.holdReason && <div className="small bad-text">{i.holdReason}</div>}
            {i.cancelReason && <div className="small muted">Reason: {i.cancelReason}</div>}
            <div className="small muted">
              {i.indentNo} · {formatDateTime(i.createdAt)}{i.acceptedByName ? ` · accepted by ${i.acceptedByName}` : ''}
              {i.txnNo && <> · <Link to={`/sales?q=${i.txnNo}`}>{i.txnNo}</Link></>}
            </div>
            <div className="card-actions">
              {canProcess && i.status === 'PENDING' && <button onClick={() => accept(i)} disabled={act.busy}>Accept</button>}
              {canProcess && (i.status === 'PENDING' || i.status === 'ACCEPTED') && can('CREDIT_TXN_CREATE') && (
                <button className="primary auto" onClick={() => navigate(`/sales/new?indent=${i.id}`)}>Fill &amp; bill</button>
              )}
              {canProcess && (i.status === 'PENDING' || i.status === 'ACCEPTED') && (
                <button className="link" onClick={() => setDialog({ kind: 'reject', indent: i })}>Reject</button>
              )}
              {canAuthorise && i.status === 'ON_HOLD_CREDIT_LIMIT' && <>
                <button className="primary auto" onClick={() => setDialog({ kind: 'authorise', indent: i })}>Authorise</button>
                <button className="link" onClick={() => setDialog({ kind: 'reject', indent: i })}>Reject</button>
              </>}
              {isCustomer && (i.status === 'PENDING' || i.status === 'ON_HOLD_CREDIT_LIMIT') && (
                <button className="link" onClick={() => setDialog({ kind: 'cancel', indent: i })}>Cancel indent</button>
              )}
            </div>
          </div>
        ))}
        {loaded && !list.busy && rows.length === 0 && <div className="card empty">No indents here</div>}
      </div>

      {(dialog?.kind === 'reject' || dialog?.kind === 'cancel') && (
        <ReasonModal title={`${dialog.kind === 'cancel' ? 'Cancel' : 'Reject'} indent ${dialog.indent.indentNo}`}
          confirmLabel={dialog.kind === 'cancel' ? 'Cancel indent' : 'Reject indent'} danger onClose={() => setDialog(null)}
          onConfirm={(reason) => api(`/indents/${dialog.indent.id}/${dialog.kind}`, { method: 'POST', body: JSON.stringify({ reason }) }).then(load)} />
      )}
      {dialog?.kind === 'authorise' && <AuthoriseDialog indent={dialog.indent} onClose={() => setDialog(null)} onDone={load} />}
    </>
  );
}

function AuthoriseDialog({ indent, onClose, onDone }: { indent: Indent; onClose: () => void; onDone: () => void }) {
  // datetime-local wants local wall-clock time, not UTC.
  const inThreeDays = new Date(Date.now() + 3 * 86400000 - new Date().getTimezoneOffset() * 60000);
  const [extra, setExtra] = useState('');
  const [until, setUntil] = useState(inThreeDays.toISOString().slice(0, 16));
  return (
    <ReasonModal title={`Authorise ${indent.indentNo}`} confirmLabel="Authorise indent" onClose={onClose}
      onConfirm={(reason) => api(`/indents/${indent.id}/authorise`, {
        method: 'POST',
        body: JSON.stringify({
          reason,
          extraLimit: extra ? Number(extra) : null,
          validUntil: extra ? new Date(until).toISOString() : null,
        }),
      }).then(onDone)}>
      <p className="small bad-text">{indent.holdReason}</p>
      <p className="muted small">Grant a temporary exception to release it. Leave the amount empty if you have already raised the limit or the customer has paid.</p>
      <div className="grid2">
        <Field label="Extra limit (₹)"><input type="number" min={1} step="0.01" value={extra} onChange={(e) => setExtra(e.target.value)} /></Field>
        <Field label="Valid until"><input type="datetime-local" value={until} onChange={(e) => setUntil(e.target.value)} disabled={!extra} /></Field>
      </div>
    </ReasonModal>
  );
}
