import { useState } from 'react';
import { apiDownload, formatDate, formatDateTime, formatINR } from '../api/client';
import type { Report, ReportKind, ReportValue } from '../api/types';
import { ErrorBox } from './ui';

const NUMERIC: ReportKind[] = ['MONEY', 'QTY', 'INT', 'PERCENT'];

export function formatCell(v: ReportValue, kind: ReportKind): string {
  if (v == null || v === '') return '';
  // Labels such as "Total" can sit in a date column.
  if ((kind === 'DATE' || kind === 'DATETIME') && Number.isNaN(Date.parse(String(v)))) return String(v);
  switch (kind) {
    case 'MONEY': return formatINR(Number(v));
    case 'QTY': return new Intl.NumberFormat('en-IN', { maximumFractionDigits: 3 }).format(Number(v));
    case 'INT': return String(v);
    case 'PERCENT': return `${v}%`;
    case 'DATE': return formatDate(String(v));
    case 'DATETIME': return formatDateTime(String(v));
    default: return String(v);
  }
}

/** Excel / PDF buttons for a report endpoint; the query string is the one used for the on-screen view. */
export function ExportButtons({ path, name }: { path: string; name: string }) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const go = (fmt: 'xlsx' | 'pdf') => {
    setBusy(fmt); setError(null);
    apiDownload(`${path}${path.includes('?') ? '&' : '?'}format=${fmt}`, `${name}.${fmt}`)
      .catch((e) => setError(e.message)).finally(() => setBusy(null));
  };
  return (
    <div className="actions export">
      <button onClick={() => go('xlsx')} disabled={!!busy}>{busy === 'xlsx' ? 'Preparing…' : <><i className="bi bi-file-earmark-spreadsheet" />Excel</>}</button>
      <button onClick={() => go('pdf')} disabled={!!busy}>{busy === 'pdf' ? 'Preparing…' : <><i className="bi bi-file-earmark-pdf" />PDF</>}</button>
      <ErrorBox error={error} />
    </div>
  );
}

/** Summary tiles + table for any server Report. */
export default function ReportView({ report, highlight }: {
  report: Report;
  /** Column key whose positive values are shown in red (e.g. overdue). */
  highlight?: string;
}) {
  const hi = highlight ? report.columns.findIndex((c) => c.key === highlight) : -1;
  return (
    <>
      {report.subtitle.map((s) => <div key={s} className="muted small">{s}</div>)}
      {report.summary.length > 0 && (
        <div className="tiles report-tiles">
          {report.summary.map((s) => (
            <div className="card tile" key={s.label}>
              <div className="muted small">{s.label}</div>
              <div className="value">{formatCell(s.value, s.kind) || '0'}</div>
            </div>
          ))}
        </div>
      )}
      <div className="table-wrap card flush">
        <table className="report">
          <thead>
            <tr>{report.columns.map((c) => <th key={c.key} className={NUMERIC.includes(c.kind) ? 'num' : ''}>{c.header}</th>)}</tr>
          </thead>
          <tbody>
            {report.rows.map((r, i) => (
              <tr key={i}>
                {report.columns.map((c, j) => (
                  <td key={c.key} className={`${NUMERIC.includes(c.kind) ? 'num' : ''}${j === hi && Number(r[j]) > 0 ? ' bad-text' : ''}${c.kind === 'DATETIME' || c.kind === 'DATE' ? ' small' : ''}`}>
                    {formatCell(r[j], c.kind)}
                  </td>
                ))}
              </tr>
            ))}
            {report.rows.length === 0 && <tr><td colSpan={report.columns.length} className="empty">Nothing in this period</td></tr>}
          </tbody>
          {report.totals && report.rows.length > 0 && (
            <tfoot>
              <tr>{report.columns.map((c, j) => <td key={c.key} className={NUMERIC.includes(c.kind) ? 'num' : ''}><b>{c.kind === 'PERCENT' ? '' : formatCell(report.totals![j], c.kind)}</b></td>)}</tr>
            </tfoot>
          )}
        </table>
      </div>
    </>
  );
}
