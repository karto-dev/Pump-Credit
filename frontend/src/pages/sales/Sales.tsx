import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, formatDateTime, formatINR, formatQty, isoDay } from '../../api/client';
import type { ApprovalStatus, Sale } from '../../api/types';
import { useAuth } from '../../auth';
import BillViewer from '../../components/BillViewer';
import { ApprovalBadge, APPROVAL_LABELS } from '../../components/status';
import { ErrorBox, useAction } from '../../components/ui';

const ANSWERABLE = ['AWAITING_CUSTOMER_APPROVAL', 'NO_RESPONSE'];

type SortKey = 'suppliedAt' | 'customerName' | 'amount' | 'quantity';

export default function Sales() {
  const { user, can } = useAuth();
  const isCustomer = user?.role === 'CUSTOMER';
  const [params] = useSearchParams();
  const monthStart = isoDay(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
  // A search or approval link should find the bill whatever its date.
  const unbounded = params.get('q') || params.get('approval');
  const [from, setFrom] = useState(unbounded ? '' : monthStart);
  const [to, setTo] = useState(unbounded ? '' : isoDay());
  const [q, setQ] = useState(params.get('q') ?? '');
  const [fuel, setFuel] = useState('');
  const [approval, setApproval] = useState(params.get('approval') ?? '');
  const [rows, setRows] = useState<Sale[]>([]);
  const [loaded, setLoaded] = useState(false); // avoid flashing "empty" before the first response
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'suppliedAt', desc: true });
  const [viewing, setViewing] = useState<Sale | null>(null);
  const list = useAction();

  const load = useCallback(() => {
    const p = new URLSearchParams();
    if (from) p.set('from', from);
    if (to) p.set('to', to);
    if (q) p.set('q', q);
    if (fuel) p.set('fuelType', fuel);
    if (approval) p.set('approvalStatus', approval);
    list.run(() => api<Sale[]>(`/credit-sales?${p}`).then((r) => { setRows(r); setLoaded(true); }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [from, to, q, fuel, approval]);

  useEffect(() => {
    const t = setTimeout(load, 250);
    return () => clearTimeout(t);
  }, [load]);

  const sorted = useMemo(() => {
    const r = [...rows].sort((a, b) => {
      const x = a[sort.key], y = b[sort.key];
      return typeof x === 'number' ? (x as number) - (y as number) : String(x).localeCompare(String(y));
    });
    return sort.desc ? r.reverse() : r;
  }, [rows, sort]);

  const totals = useMemo(() => ({
    litres: rows.reduce((s, r) => s + Number(r.quantity), 0),
    amount: rows.reduce((s, r) => s + Number(r.amount), 0),
  }), [rows]);

  const th = (key: SortKey, label: string, cls = '') => (
    <th className={`sortable ${cls}`} onClick={() => setSort({ key, desc: sort.key === key ? !sort.desc : true })}>
      {label}{sort.key === key ? (sort.desc ? ' ▾' : ' ▴') : ''}
    </th>
  );

  return (
    <>
      <div className="page-head">
        <h2>{isCustomer ? 'My purchases' : 'Credit sales'}</h2>
        {can('CREDIT_TXN_CREATE') && <Link to="/sales/new" className="button primary-link"><i className="bi bi-plus-lg" />New sale</Link>}
      </div>
      <div className="filters">
        <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From date" className="date" />
        <input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="To date" className="date" />
        <input placeholder={isCustomer ? 'Search vehicle or bill no.' : 'Search customer, vehicle, bill or txn no.'} value={q} onChange={(e) => setQ(e.target.value)} />
        <select value={fuel} onChange={(e) => setFuel(e.target.value)}>
          <option value="">All fuel</option><option value="DIESEL">Diesel</option><option value="PETROL">Petrol</option>
        </select>
        <select value={approval} onChange={(e) => setApproval(e.target.value)}>
          <option value="">Any approval</option>
          {(Object.keys(APPROVAL_LABELS) as ApprovalStatus[]).map((k) => <option key={k} value={k}>{APPROVAL_LABELS[k]}</option>)}
        </select>
      </div>
      <ErrorBox error={list.error} />
      <div className="summary">
        <span><b>{rows.length}</b> sales</span>
        <span><b>{formatQty(totals.litres)}</b></span>
        <span><b>{formatINR(totals.amount)}</b></span>
      </div>

      <div className="table-wrap card flush">
        <table>
          <thead>
            <tr>
              {th('suppliedAt', 'Date')}
              <th>Txn / bill</th>
              {!isCustomer && th('customerName', 'Customer')}
              <th>Vehicle</th><th>Fuel</th>
              {th('quantity', 'Qty', 'num')}
              <th className="num">Rate</th>
              {th('amount', 'Amount', 'num')}
              {!isCustomer && <th>Cashier</th>}
              <th>Approval</th><th />
            </tr>
          </thead>
          <tbody>
            {sorted.map((s) => (
              <tr key={s.id}>
                <td className="small">{formatDateTime(s.suppliedAt)}</td>
                <td className="small"><span className="mono">{s.txnNo}</span>{s.physicalBillNo && <div className="muted">#{s.physicalBillNo}</div>}</td>
                {!isCustomer && <td>{s.customerName}</td>}
                <td className="mono">{s.vehicleNumber}</td>
                <td>{s.fuelType}</td>
                <td className="num">{formatQty(s.quantity)}</td>
                <td className="num">{formatINR(s.ratePerLitre)}</td>
                <td className="num"><b>{formatINR(s.amount)}</b></td>
                {!isCustomer && <td className="small">{s.cashierName}</td>}
                <td><ApprovalBadge status={s.approvalStatus} />{s.status === 'REVERSED' && <div className="small bad-text">Reversed</div>}</td>
                <td>{s.hasBill && (
                  <button className={isCustomer && ANSWERABLE.includes(s.approvalStatus) && s.status === 'POSTED' ? 'primary tiny' : 'link'}
                    onClick={() => setViewing(s)}>
                    {isCustomer && ANSWERABLE.includes(s.approvalStatus) && s.status === 'POSTED' ? 'Review' : 'View bill'}
                  </button>
                )}</td>
              </tr>
            ))}
            {loaded && !list.busy && rows.length === 0 && <tr><td colSpan={11} className="empty">No sales in this period</td></tr>}
          </tbody>
        </table>
      </div>
      {viewing && <BillViewer sale={viewing} onClose={() => setViewing(null)}
        onChanged={(u) => setRows((r) => r.map((x) => (x.id === u.id ? u : x)))} />}
    </>
  );
}
