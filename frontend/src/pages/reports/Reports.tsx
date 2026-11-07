import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, isoDay } from '../../api/client';
import type { Report } from '../../api/types';
import { useAuth } from '../../auth';
import ReportView, { ExportButtons } from '../../components/ReportView';
import { ErrorBox, useAction } from '../../components/ui';

type Tab = 'outstanding' | 'sales' | 'collections' | 'cashiers';

const TABS: { key: Tab; label: string; perm: string }[] = [
  { key: 'outstanding', label: 'Outstanding & ageing', perm: 'REPORT_FINANCIAL' },
  { key: 'sales', label: 'Credit sales', perm: 'REPORT_VIEW' },
  { key: 'collections', label: 'Recoveries', perm: 'REPORT_FINANCIAL' },
  { key: 'cashiers', label: 'Cashier day book', perm: 'REPORT_VIEW' },
];

export default function Reports() {
  const { can } = useAuth();
  const tabs = TABS.filter((t) => can(t.perm));
  const [params, setParams] = useSearchParams();
  const tab = (tabs.find((t) => t.key === params.get('r'))?.key ?? tabs[0]?.key) as Tab | undefined;
  const now = new Date();
  const [from, setFrom] = useState(isoDay(new Date(now.getFullYear(), now.getMonth(), 1)));
  const [to, setTo] = useState(isoDay());
  const [groupBy, setGroupBy] = useState('day');
  const [overdueOnly, setOverdueOnly] = useState(params.get('overdue') === '1');
  const [report, setReport] = useState<Report | null>(null);
  const load = useAction();

  const path = useMemo(() => {
    switch (tab) {
      case 'outstanding': return `/reports/outstanding?overdueOnly=${overdueOnly}`;
      case 'sales': return `/reports/sales?from=${from}&to=${to}&groupBy=${groupBy}`;
      case 'collections': return `/reports/collections?from=${from}&to=${to}`;
      case 'cashiers': return `/reports/cashiers?from=${from}&to=${to}`;
      default: return null;
    }
  }, [tab, from, to, groupBy, overdueOnly]);

  useEffect(() => {
    if (!path) return;
    setReport(null);
    load.run(() => api<Report>(path).then(setReport));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path]);

  if (!tab) return <div className="card empty">You do not have access to reports</div>;

  return (
    <>
      <div className="page-head">
        <h2>Reports</h2>
        {path && can('REPORT_EXPORT') && <ExportButtons path={path} name={tab} />}
      </div>
      <div className="tabs">
        {tabs.map((t) => (
          <button key={t.key} className={tab === t.key ? 'selected' : ''} onClick={() => setParams({ r: t.key })}>{t.label}</button>
        ))}
      </div>
      <div className="filters">
        {tab !== 'outstanding' && <>
          <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} aria-label="From" className="date" />
          <input type="date" value={to} min={from} max={isoDay()} onChange={(e) => setTo(e.target.value)} aria-label="To" className="date" />
        </>}
        {tab === 'sales' && (
          <select value={groupBy} onChange={(e) => setGroupBy(e.target.value)} aria-label="Group by">
            <option value="day">By day</option>
            <option value="customer">By customer</option>
            <option value="vehicle">By vehicle</option>
            <option value="fuel">By fuel</option>
            <option value="cashier">By cashier</option>
            <option value="detail">Every sale</option>
          </select>
        )}
        {tab === 'outstanding' && (
          <label className="check"><input type="checkbox" checked={overdueOnly} onChange={(e) => setOverdueOnly(e.target.checked)} /> Overdue only</label>
        )}
      </div>
      <ErrorBox error={load.error} />
      {load.busy && !report && <div className="muted">Loading…</div>}
      {report && <ReportView report={report} highlight={tab === 'outstanding' ? 'overdue' : undefined} />}
    </>
  );
}
