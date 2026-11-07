import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, isoDay } from '../api/client';
import type { Report } from '../api/types';
import { useAuth } from '../auth';
import ReportView, { ExportButtons } from '../components/ReportView';
import { ErrorBox, useAction } from '../components/ui';

/** Indian financial year starts 1 April. */
function presets() {
  const now = new Date();
  const y = now.getFullYear(), m = now.getMonth();
  const fyStart = new Date(m >= 3 ? y : y - 1, 3, 1);
  return [
    { label: 'This month', from: isoDay(new Date(y, m, 1)), to: isoDay(now) },
    { label: 'Last month', from: isoDay(new Date(y, m - 1, 1)), to: isoDay(new Date(y, m, 0)) },
    { label: 'Last 3 months', from: isoDay(new Date(y, m - 2, 1)), to: isoDay(now) },
    { label: 'This FY', from: isoDay(fyStart), to: isoDay(now) },
  ];
}

/** Statement of account: /statement for the customer, /customers/:id/statement for staff. */
export default function Statement() {
  const { user } = useAuth();
  const params = useParams();
  const customerId = params.id ? Number(params.id) : user?.customerId;
  const p = presets();
  const [from, setFrom] = useState(p[0].from);
  const [to, setTo] = useState(p[0].to);
  const [report, setReport] = useState<Report | null>(null);
  const load = useAction();

  useEffect(() => {
    if (!customerId || !from || !to) return;
    load.run(() => api<Report>(`/customers/${customerId}/statement?from=${from}&to=${to}`).then(setReport));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customerId, from, to]);

  if (!customerId) return <div className="card empty">No account linked to this login</div>;
  const path = `/customers/${customerId}/statement?from=${from}&to=${to}`;

  return (
    <>
      <div className="page-head">
        <div>
          {params.id && <Link to={`/customers/${customerId}`} className="muted small"><i className="bi bi-arrow-left" /> Customer</Link>}
          <h2>Statement of account</h2>
        </div>
        <ExportButtons path={path} name="statement" />
      </div>
      <div className="filters">
        <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} aria-label="From" className="date" />
        <input type="date" value={to} min={from} max={isoDay()} onChange={(e) => setTo(e.target.value)} aria-label="To" className="date" />
        <div className="tabs inline">
          {p.map((x) => (
            <button key={x.label} className={from === x.from && to === x.to ? 'selected' : ''}
                    onClick={() => { setFrom(x.from); setTo(x.to); }}>{x.label}</button>
          ))}
        </div>
      </div>
      <ErrorBox error={load.error} />
      {report && <ReportView report={report} />}
      {report && <p className="small muted">Payments count only after the pump verifies them. Payments under verification are listed above but not deducted.</p>}
    </>
  );
}
