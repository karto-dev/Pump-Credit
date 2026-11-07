import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, formatINR, isoDay } from '../api/client';
import type { CustomerDetail, DisputeSummary, Indent, PaymentSummary, Sale, StaffDashboard } from '../api/types';
import { useAuth } from '../auth';

type Tile = { label: string; value: string; sub?: string; to?: string; bad?: boolean };

const ICONS: [RegExp, string][] = [
  [/outstanding/i, 'bi-cash-stack'], [/overdue/i, 'bi-hourglass-split'], [/available credit/i, 'bi-credit-card'],
  [/sales|purchases|this month/i, 'bi-fuel-pump'], [/recovery/i, 'bi-graph-up-arrow'], [/verify|verification/i, 'bi-patch-check'],
  [/cash/i, 'bi-cash-coin'], [/over limit/i, 'bi-speedometer2'], [/indent/i, 'bi-clipboard-check'], [/users/i, 'bi-people'],
  [/exception/i, 'bi-unlock'], [/approval/i, 'bi-hand-thumbs-up'], [/disput/i, 'bi-exclamation-octagon'],
  [/adjustment/i, 'bi-sliders'],
];
const iconFor = (label: string) => ICONS.find(([re]) => re.test(label))?.[1] ?? 'bi-circle';

export default function Dashboard() {
  const { user, can } = useAuth();
  const [tiles, setTiles] = useState<Tile[] | null>(null);

  useEffect(() => {
    if (!user) return;
    const today = isoDay();
    const monthStart = isoDay(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
    const sum = (rows: Sale[]) => rows.reduce((s, r) => s + Number(r.amount), 0);

    if (user.role === 'CUSTOMER' && user.customerId) {
      Promise.all([
        api<CustomerDetail>(`/customers/${user.customerId}`),
        api<Sale[]>(`/credit-sales?from=${today}&to=${today}`),
        api<Sale[]>(`/credit-sales?from=${monthStart}&to=${today}`),
      ]).then(([c, todays, month]) => setTiles([
        { label: 'Outstanding balance', value: formatINR(c.confirmedOutstanding), to: '/statement', sub: 'View statement' },
        { label: 'Available credit', value: formatINR(c.availableCredit), bad: Number(c.availableCredit) < 0,
          sub: `Limit ${formatINR(Number(c.creditLimit) + Number(c.activeExceptionLimit))}` },
        { label: "Today's purchases", value: formatINR(sum(todays)), sub: `${todays.length} fills`, to: '/sales' },
        { label: 'This month', value: formatINR(sum(month)), sub: `${month.length} fills`, to: '/sales' },
        { label: 'Bills awaiting approval', value: String(c.pendingApprovalCount), sub: formatINR(c.pendingApprovalAmount),
          to: '/sales?approval=AWAITING_CUSTOMER_APPROVAL' },
        { label: 'Payments under verification', value: formatINR(c.unverifiedPaymentClaims), sub: 'Not deducted until verified',
          to: '/payments' },
      ])).catch(() => setTiles([]));
    } else if (user.role === 'CASHIER') {
      Promise.all([
        api<Indent[]>('/indents?status=PENDING,ACCEPTED'),
        api<Sale[]>(`/credit-sales?from=${today}&to=${today}`),
        api<PaymentSummary>('/payments/summary').catch(() => null),
      ]).then(([open, todays, pay]) => setTiles([
        { label: 'Open indents', value: String(open.length), to: '/indents' },
        { label: 'Credit sales today', value: formatINR(sum(todays)), sub: `${todays.length} fills`, to: '/sales' },
        { label: 'My sales today', value: String(todays.filter((s) => s.cashierId === user.id).length), to: '/sales' },
        ...(pay ? [{ label: 'Cash claims to collect', value: String(pay.awaitingCashCount), sub: formatINR(pay.awaitingCashAmount),
          to: '/payments?status=AWAITING_CASHIER_ENTRY', bad: pay.awaitingCashCount > 0 }] : []),
      ])).catch(() => setTiles([]));
    } else {
      Promise.all([
        api<StaffDashboard>('/dashboard/staff').catch(() => null),
        api<DisputeSummary>('/disputes/summary').catch(() => null),
      ]).then(([s, d]) => {
        const t: Tile[] = [];
        if (s) {
          t.push(
            { label: 'Total outstanding', value: formatINR(s.totalOutstanding), sub: `${s.customersWithBalance} customers`,
              to: can('REPORT_FINANCIAL') ? '/reports?r=outstanding' : undefined },
            { label: 'Overdue', value: formatINR(s.overdueAmount), sub: `${s.overdueCustomers} customers past credit period`,
              bad: s.overdueCustomers > 0, to: can('REPORT_FINANCIAL') ? '/reports?r=outstanding&overdue=1' : undefined },
            { label: 'Credit sales today', value: formatINR(s.salesToday), sub: `${s.fillsToday} fills`, to: '/sales' },
            { label: 'Credit sales this month', value: formatINR(s.salesThisMonth), to: can('REPORT_VIEW') ? '/reports?r=sales' : '/sales' },
            { label: 'Verified recovery (month)', value: formatINR(s.verifiedThisMonth), sub: `${formatINR(s.verifiedToday)} today`,
              to: can('REPORT_FINANCIAL') ? '/reports?r=collections' : undefined },
            { label: 'Payments to verify', value: String(s.pendingVerification), sub: formatINR(s.pendingVerificationAmount),
              to: '/payments?status=AWAITING_VERIFICATION', bad: s.pendingVerification > 0 },
            { label: 'Cash claims to collect', value: String(s.cashToCollect), to: '/payments?status=AWAITING_CASHIER_ENTRY' },
            { label: 'Customers over limit', value: String(s.customersOverLimit), bad: s.customersOverLimit > 0,
              to: '/customers?overLimit=1' },
            { label: 'Indents on hold', value: String(s.heldIndents), bad: s.heldIndents > 0, to: '/indents' },
          );
          if (user.role === 'ADMIN') {
            t.push({ label: 'Active users', value: String(s.activeUsers), to: '/users' },
              { label: 'Active limit exceptions', value: String(s.activeExceptions) });
          }
        }
        if (d) {
          t.push(
            { label: 'Pending customer approvals', value: String(d.awaitingCount),
              sub: `${formatINR(d.awaitingAmount)} · ${d.noResponseCount} no response`, to: '/sales?approval=AWAITING_CUSTOMER_APPROVAL' },
            { label: 'Disputed bills', value: String(d.openDisputes + d.underInvestigation),
              sub: `${d.openDisputes} not taken up · ${formatINR(d.disputedAmount)}`, to: '/disputes', bad: d.openDisputes > 0 },
          );
          if (can('ADJUSTMENT_APPROVE')) {
            t.push({ label: 'Adjustments to approve', value: String(d.pendingAdjustments), to: '/adjustments',
              bad: d.pendingAdjustments > 0 });
          }
        }
        setTiles(t);
      });
    }
    // can() is derived from user, which is the real dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  if (!user) return null;

  return (
    <>
      <div className="page-head">
        <div>
          <h2>{user.role === 'CUSTOMER' ? `Hello, ${user.fullName}` : `${user.role.charAt(0) + user.role.slice(1).toLowerCase()} dashboard`}</h2>
          <div className="muted">{new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}</div>
        </div>
        <div className="actions">
          {user.role === 'CUSTOMER' && <Link to="/indents/new" className="button primary-link"><i className="bi bi-plus-lg" />New indent</Link>}
          {user.role === 'CUSTOMER' && can('PAYMENT_SUBMIT') && <Link to="/payments/new" className="button"><i className="bi bi-qr-code-scan" />Report a payment</Link>}
          {user.role === 'CASHIER' && <Link to="/sales/new" className="button primary-link"><i className="bi bi-plus-lg" />New sale</Link>}
          {user.role === 'CASHIER' && can('PAYMENT_RECORD') && <Link to="/payments/record" className="button"><i className="bi bi-cash-coin" />Record payment</Link>}
        </div>
      </div>
      <div className="tiles">
        {tiles === null && [0, 1, 2, 3].map((i) => (
          <div className="card tile placeholder-glow" key={i}>
            <span className="placeholder col-3 rounded-3 mb-3" style={{ height: 38 }} />
            <span className="placeholder col-8 d-block mb-2" /><span className="placeholder col-5 d-block" style={{ height: 24 }} />
          </div>
        ))}
        {(tiles ?? []).map((t) => {
          const body = (
            <>
              <div className="tile-icon"><i className={`bi ${iconFor(t.label)}`} /></div>
              {t.to && <i className="bi bi-arrow-right tile-go" />}
              <div className="muted">{t.label}</div>
              <div className={`value${t.bad ? ' bad-text' : ''}`}>{t.value}</div>
              {t.sub && <div className="small muted">{t.sub}</div>}
            </>
          );
          return t.to
            ? <Link to={t.to} className={`card tile link-tile${t.bad ? ' is-bad' : ''}`} key={t.label}>{body}</Link>
            : <div className={`card tile${t.bad ? ' is-bad' : ''}`} key={t.label}>{body}</div>;
        })}
      </div>
      <details className="card perms">
        <summary>My permissions ({user.permissions.length})</summary>
        <ul>{user.permissions.map((p) => <li key={p}>{p}</li>)}</ul>
      </details>
    </>
  );
}
