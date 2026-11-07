import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, formatDate, formatDateTime, formatINR } from '../../api/client';
import type { Payment } from '../../api/types';
import { useAuth } from '../../auth';
import PaymentViewer from '../../components/PaymentViewer';
import { PaymentBadge } from '../../components/status';
import { ErrorBox, useAction } from '../../components/ui';

const STAFF_TABS = [
  { key: 'AWAITING_VERIFICATION', label: 'To verify' },
  { key: 'AWAITING_CASHIER_ENTRY', label: 'Cash to collect' },
  { key: 'ADDITIONAL_PROOF_REQUIRED', label: 'Proof requested' },
  { key: 'VERIFIED', label: 'Verified' },
  { key: 'REJECTED,DUPLICATE_PAYMENT,CANCELLED', label: 'Closed' },
  { key: '', label: 'All' },
];

const CUSTOMER_TABS = [
  { key: 'AWAITING_CASHIER_ENTRY,AWAITING_VERIFICATION,ADDITIONAL_PROOF_REQUIRED', label: 'In progress' },
  { key: 'VERIFIED', label: 'Verified' },
  { key: '', label: 'All' },
];

export default function Payments() {
  const { user, can } = useAuth();
  const isCustomer = user?.role === 'CUSTOMER';
  const tabs = isCustomer ? CUSTOMER_TABS : STAFF_TABS;
  const [params] = useSearchParams();
  const [tab, setTab] = useState(params.get('status') ?? (isCustomer ? '' : can('PAYMENT_VERIFY') ? 'AWAITING_VERIFICATION' : 'AWAITING_CASHIER_ENTRY'));
  const [q, setQ] = useState(params.get('q') ?? '');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [method, setMethod] = useState('');
  const [rows, setRows] = useState<Payment[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [viewing, setViewing] = useState<Payment | null>(null);
  const list = useAction();

  const load = useCallback(() => {
    const p = new URLSearchParams();
    if (tab) p.set('status', tab);
    if (q) p.set('q', q);
    if (from) p.set('from', from);
    if (to) p.set('to', to);
    if (method) p.set('method', method);
    list.run(() => api<Payment[]>(`/payments?${p}`).then((r) => { setRows(r); setLoaded(true); }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, q, from, to, method]);

  useEffect(() => {
    const t = setTimeout(load, 250);
    return () => clearTimeout(t);
  }, [load]);

  // Notification deep link: /payments?status=&open=12
  useEffect(() => {
    const id = Number(params.get('open'));
    if (id) api<Payment>(`/payments/${id}`).then(setViewing).catch(() => undefined);
  }, [params]);

  const total = useMemo(() => rows.reduce((s, r) => s + Number(r.verifiedAmount ?? r.amount), 0), [rows]);

  return (
    <>
      <div className="page-head">
        <h2>{isCustomer ? 'My payments' : 'Payments'}</h2>
        <div className="actions">
          {isCustomer && can('PAYMENT_SUBMIT') && <Link to="/payments/new" className="button primary-link"><i className="bi bi-plus-lg" />Report a payment</Link>}
          {!isCustomer && can('PAYMENT_RECORD') && <Link to="/payments/record" className="button primary-link"><i className="bi bi-plus-lg" />Record payment</Link>}
        </div>
      </div>
      <div className="tabs">
        {tabs.map((t) => (
          <button key={t.label} className={tab === t.key ? 'selected' : ''} onClick={() => setTab(t.key)}>{t.label}</button>
        ))}
      </div>
      <div className="filters">
        <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="Paid from" className="date" />
        <input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="Paid to" className="date" />
        <input placeholder={isCustomer ? 'Search payment or UTR no.' : 'Search customer, payment or UTR no.'} value={q} onChange={(e) => setQ(e.target.value)} />
        <select value={method} onChange={(e) => setMethod(e.target.value)}>
          <option value="">Any method</option>
          {['UPI', 'NEFT', 'IMPS', 'RTGS', 'CASH', 'CHEQUE'].map((m) => <option key={m}>{m}</option>)}
        </select>
      </div>
      <ErrorBox error={list.error} />
      <div className="summary"><span><b>{rows.length}</b> payments</span><span><b>{formatINR(total)}</b></span></div>

      <div className="table-wrap card flush">
        <table>
          <thead>
            <tr>
              <th>Paid on</th><th>Payment</th>
              {!isCustomer && <th>Customer</th>}
              <th>Method / ref</th><th className="num">Amount</th><th>Entered by</th><th>Status</th><th />
            </tr>
          </thead>
          <tbody>
            {rows.map((p) => (
              <tr key={p.id} className="clickable" onClick={() => setViewing(p)}>
                <td className="small">{formatDate(p.paymentDate)}</td>
                <td className="small"><span className="mono">{p.paymentNo}</span><div className="muted">{formatDateTime(p.createdAt)}</div></td>
                {!isCustomer && <td>{p.customerName}</td>}
                <td className="small">{p.method}{p.referenceNo && <div className="mono muted">{p.referenceNo}</div>}</td>
                <td className="num">
                  <b>{formatINR(p.verifiedAmount ?? p.amount)}</b>
                  {p.verifiedAmount != null && Number(p.verifiedAmount) < Number(p.amount) && <div className="small warn-text">of {formatINR(p.amount)}</div>}
                </td>
                <td className="small">
                  {p.submissionNo && <div>Customer</div>}
                  {p.recordedByName && <div>{p.recordedByName}</div>}
                </td>
                <td>
                  <PaymentBadge status={p.status} />
                  {p.duplicateFlag && p.status !== 'DUPLICATE_PAYMENT' && <span className="badge warn">dup?</span>}
                </td>
                <td className="small muted">{p.proofCount > 0 ? <><i className="bi bi-paperclip" />{p.proofCount}</> : ''}</td>
              </tr>
            ))}
            {loaded && !list.busy && rows.length === 0 && <tr><td colSpan={8} className="empty">No payments here</td></tr>}
          </tbody>
        </table>
      </div>
      {viewing && <PaymentViewer payment={viewing} onClose={() => setViewing(null)}
        onChanged={(u) => setRows((r) => r.map((x) => (x.id === u.id ? u : x)))} />}
    </>
  );
}
