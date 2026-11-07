import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api, formatINR } from '../../api/client';
import type { CustomerDetail, CustomerRow } from '../../api/types';
import { useAuth } from '../../auth';
import { Badge, ErrorBox, Modal, useAction } from '../../components/ui';
import CustomerForm, { EMPTY_PROFILE } from './CustomerForm';

export function utilisation(c: { creditLimit: number; activeExceptionLimit: number; confirmedOutstanding: number }) {
  const limit = Number(c.creditLimit) + Number(c.activeExceptionLimit);
  if (limit <= 0) return Number(c.confirmedOutstanding) > 0 ? 100 : 0;
  return Math.round((Number(c.confirmedOutstanding) / limit) * 100);
}

export function UtilBar({ pct }: { pct: number }) {
  const tone = pct > 100 ? 'bad' : pct >= 80 ? 'warn' : 'ok';
  return (
    <div className="util" title={`${pct}% used`}>
      <div className={`util-fill ${tone}`} style={{ width: `${Math.min(pct, 100)}%` }} />
      <span>{pct}%</span>
    </div>
  );
}

export const statusTone = (s: string) => (s === 'ACTIVE' ? 'ok' : s === 'SUSPENDED' ? 'warn' : 'bad');

export default function Customers() {
  const { can } = useAuth();
  const navigate = useNavigate();
  const [rows, setRows] = useState<CustomerRow[]>([]);
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('');
  const [params] = useSearchParams();
  const [overLimit, setOverLimit] = useState(params.get('overLimit') === '1');
  const [creating, setCreating] = useState(false);
  const list = useAction();
  const create = useAction();

  const load = useCallback(() => {
    const params = new URLSearchParams();
    if (q) params.set('q', q);
    if (status) params.set('status', status);
    if (overLimit) params.set('overLimit', 'true');
    list.run(() => api<CustomerRow[]>(`/customers?${params}`).then(setRows));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, status, overLimit]);

  useEffect(() => {
    const t = setTimeout(load, 250); // debounce typing
    return () => clearTimeout(t);
  }, [load]);

  return (
    <>
      <div className="page-head">
        <h2>Customers</h2>
        {can('CUSTOMER_MANAGE') && <button className="primary auto" onClick={() => setCreating(true)}><i className="bi bi-plus-lg" />New customer</button>}
      </div>

      <div className="filters">
        <input placeholder="Search name, phone, code or vehicle no." value={q} onChange={(e) => setQ(e.target.value)} />
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All statuses</option>
          <option value="ACTIVE">Active</option>
          <option value="SUSPENDED">Suspended</option>
          <option value="CLOSED">Closed</option>
        </select>
        <label className="check">
          <input type="checkbox" checked={overLimit} onChange={(e) => setOverLimit(e.target.checked)} /> Over limit only
        </label>
      </div>
      <ErrorBox error={list.error} />

      <div className="table-wrap card flush">
        <table>
          <thead>
            <tr>
              <th>Customer</th><th>Phone</th><th className="num">Credit limit</th>
              <th className="num">Outstanding</th><th className="num">Available</th><th>Utilisation</th>
              <th className="num">Vehicles</th><th>Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id} className="clickable" onClick={() => navigate(`/customers/${c.id}`)}>
                <td>
                  <Link to={`/customers/${c.id}`} onClick={(e) => e.stopPropagation()}>{c.name}</Link>
                  <div className="muted small">{c.customerCode}{c.businessName ? ` · ${c.businessName}` : ''}</div>
                </td>
                <td>{c.phone}</td>
                <td className="num">
                  {formatINR(c.creditLimit)}
                  {Number(c.activeExceptionLimit) > 0 && <div className="small warn-text">+{formatINR(c.activeExceptionLimit)} exc.</div>}
                </td>
                <td className="num">{formatINR(c.confirmedOutstanding)}</td>
                <td className={`num ${Number(c.availableCredit) < 0 ? 'bad-text' : ''}`}>{formatINR(c.availableCredit)}</td>
                <td><UtilBar pct={utilisation(c)} /></td>
                <td className="num">{c.vehicleCount}</td>
                <td><Badge tone={statusTone(c.status)}>{c.status}</Badge></td>
              </tr>
            ))}
            {!list.busy && rows.length === 0 && (
              <tr><td colSpan={8} className="empty">No customers found</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {creating && (
        <Modal title="New customer" onClose={() => setCreating(false)} wide>
          <CustomerForm
            initial={EMPTY_PROFILE} mode="create" canSetLimit={can('CREDIT_LIMIT_CHANGE')}
            busy={create.busy} error={create.error}
            onSubmit={async (profile, extras) => {
              const created = await create.run(() => api<CustomerDetail>('/customers', {
                method: 'POST',
                body: JSON.stringify({
                  profile,
                  creditLimit: Number(extras.creditLimit || 0),
                  openingBalance: Number(extras.openingBalance || 0),
                  openingBalanceDate: extras.openingBalanceDate || null,
                }),
              }));
              if (created) navigate(`/customers/${created.id}`);
            }}
          />
        </Modal>
      )}
    </>
  );
}
