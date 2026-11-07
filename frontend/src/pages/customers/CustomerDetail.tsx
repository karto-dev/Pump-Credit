import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, formatDate, formatDateTime, formatINR } from '../../api/client';
import type { CreditException, CustomerDetail as Detail, LimitChange, UserRow, Vehicle } from '../../api/types';
import { useAuth } from '../../auth';
import ReasonModal from '../../components/ReasonModal';
import { Badge, ErrorBox, Field, Modal, nullIfBlank, useAction } from '../../components/ui';
import CustomerForm from './CustomerForm';
import { statusTone, utilisation, UtilBar } from './Customers';

type Dialog =
  | { kind: 'edit' }
  | { kind: 'status' }
  | { kind: 'limit' }
  | { kind: 'exception' }
  | { kind: 'revoke'; ex: CreditException }
  | { kind: 'vehicle'; vehicle?: Vehicle }
  | { kind: 'vehicleActive'; vehicle: Vehicle }
  | null;

export default function CustomerDetail() {
  const id = Number(useParams().id);
  const { user, can } = useAuth();
  const [c, setC] = useState<Detail | null>(null);
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [history, setHistory] = useState<LimitChange[]>([]);
  const [exceptions, setExceptions] = useState<CreditException[]>([]);
  const [logins, setLogins] = useState<UserRow[]>([]);
  const [dialog, setDialog] = useState<Dialog>(null);
  const page = useAction();
  const edit = useAction();

  const canGrantException = user?.role === 'ADMIN' && can('CREDIT_LIMIT_EXCEPTION');

  const load = useCallback(() => {
    page.run(async () => {
      const [d, v, h, e] = await Promise.all([
        api<Detail>(`/customers/${id}`),
        api<Vehicle[]>(`/customers/${id}/vehicles`),
        api<LimitChange[]>(`/customers/${id}/credit-limit-history`),
        api<CreditException[]>(`/customers/${id}/credit-exceptions`),
      ]);
      setC(d); setVehicles(v); setHistory(h); setExceptions(e);
      if (can('USER_MANAGE')) setLogins(await api<UserRow[]>(`/users?customerId=${id}`));
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  useEffect(load, [load]);

  if (!c) return <ErrorBox error={page.error} />;
  const close = () => setDialog(null);

  return (
    <>
      <div className="page-head">
        <div>
          <Link to="/customers" className="muted small"><i className="bi bi-arrow-left" /> Customers</Link>
          <h2>{c.name} <Badge tone={statusTone(c.status)}>{c.status}</Badge></h2>
          <div className="muted">{c.customerCode}{c.businessName ? ` · ${c.businessName}` : ''} · since {formatDate(c.createdAt)}</div>
        </div>
        <div className="actions">
          {can('LEDGER_VIEW_ALL') && <Link to={`/customers/${c.id}/statement`} className="button">Statement</Link>}
          {(can('PAYMENT_VIEW_ALL') || can('PAYMENT_VERIFY')) && (
            <Link to={`/payments?status=&q=${encodeURIComponent(c.name)}`} className="button">Payments</Link>
          )}
          {can('CUSTOMER_MANAGE') && <>
            <button onClick={() => { edit.setError(null); setDialog({ kind: 'edit' }); }}>Edit details</button>
            <button onClick={() => setDialog({ kind: 'status' })}>Change status</button>
          </>}
        </div>
      </div>
      <ErrorBox error={page.error} />

      <div className="tiles">
        <Tile label="Credit limit" value={formatINR(c.creditLimit)}
              sub={Number(c.activeExceptionLimit) > 0 ? `+${formatINR(c.activeExceptionLimit)} exception` : undefined} />
        <Tile label="Confirmed outstanding" value={formatINR(c.confirmedOutstanding)} />
        <Tile label="Available credit" value={formatINR(c.availableCredit)} bad={Number(c.availableCredit) < 0} />
        <div className="card tile"><div className="muted">Utilisation</div><UtilBar pct={utilisation(c)} /></div>
        <Tile label="Unverified payment claims" value={formatINR(c.unverifiedPaymentClaims)} sub="Not deducted until verified" />
        <Tile label="Bills awaiting approval" value={String(c.pendingApprovalCount)} sub={formatINR(c.pendingApprovalAmount)} />
        <Tile label="Disputed bills" value={String(c.disputedCount)} sub={formatINR(c.disputedAmount)} />
      </div>

      <section className="card section">
        <h3>Details</h3>
        <dl className="kv">
          <dt>Contact person</dt><dd>{c.contactPerson ?? '—'}</dd>
          <dt>Mobile</dt><dd>{c.phone}{c.alternatePhone ? ` / ${c.alternatePhone}` : ''}</dd>
          <dt>Email</dt><dd>{c.email ?? '—'}</dd>
          <dt>Address</dt><dd>{[c.address, c.city, c.state, c.pincode].filter(Boolean).join(', ') || '—'}</dd>
          <dt>GSTIN / PAN</dt><dd>{c.gstin ?? '—'} / {c.pan ?? '—'}</dd>
          <dt>Credit period</dt><dd>{c.creditPeriodDays} days</dd>
          <dt>Opening balance</dt>
          <dd>{formatINR(c.openingBalance)}{c.openingBalanceDate ? ` as on ${formatDate(c.openingBalanceDate)}` : ''}</dd>
        </dl>
      </section>

      <section className="card section">
        <div className="section-head">
          <h3>Vehicles</h3>
          {can('VEHICLE_MANAGE') && <button onClick={() => setDialog({ kind: 'vehicle' })}><i className="bi bi-plus-lg" />Add vehicle</button>}
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Number</th><th>Type</th><th>Fuel</th><th>Notes</th><th>Status</th>{can('VEHICLE_MANAGE') && <th />}</tr></thead>
            <tbody>
              {vehicles.map((v) => (
                <tr key={v.id} className={v.isActive ? '' : 'inactive'}>
                  <td className="mono">{v.vehicleNumber}</td>
                  <td>{v.vehicleType ?? '—'}</td>
                  <td>{v.defaultFuel ?? '—'}</td>
                  <td>{v.description ?? ''}</td>
                  <td><Badge tone={v.isActive ? 'ok' : 'neutral'}>{v.isActive ? 'Active' : 'Inactive'}</Badge></td>
                  {can('VEHICLE_MANAGE') && (
                    <td className="row-actions">
                      <button className="link" onClick={() => setDialog({ kind: 'vehicle', vehicle: v })}>Edit</button>
                      <button className="link" onClick={() => setDialog({ kind: 'vehicleActive', vehicle: v })}>
                        {v.isActive ? 'Deactivate' : 'Reactivate'}
                      </button>
                    </td>
                  )}
                </tr>
              ))}
              {vehicles.length === 0 && <tr><td colSpan={6} className="empty">No vehicles registered</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card section">
        <div className="section-head">
          <h3>Credit limit</h3>
          {can('CREDIT_LIMIT_CHANGE') && <button onClick={() => setDialog({ kind: 'limit' })}>Change limit</button>}
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Date</th><th className="num">From</th><th className="num">To</th><th>Reason</th><th>By</th></tr></thead>
            <tbody>
              {history.map((h) => (
                <tr key={h.id}>
                  <td>{formatDateTime(h.changedAt)}</td>
                  <td className="num">{formatINR(h.oldLimit)}</td>
                  <td className="num">{formatINR(h.newLimit)}</td>
                  <td>{h.reason}</td>
                  <td>{h.changedByName}</td>
                </tr>
              ))}
              {history.length === 0 && <tr><td colSpan={5} className="empty">No limit changes yet</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card section">
        <div className="section-head">
          <h3>Temporary limit exceptions</h3>
          {canGrantException && <button onClick={() => setDialog({ kind: 'exception' })}><i className="bi bi-plus-lg" />Authorise exception</button>}
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th className="num">Extra limit</th><th>Valid</th><th>Reason</th><th>Authorised by</th><th>Status</th>{canGrantException && <th />}</tr></thead>
            <tbody>
              {exceptions.map((e) => (
                <tr key={e.id}>
                  <td className="num">{formatINR(e.extraLimit)}</td>
                  <td className="small">{formatDateTime(e.validFrom)} → {formatDateTime(e.validUntil)}</td>
                  <td>{e.reason}{e.revokeReason && <div className="small muted">Revoked: {e.revokeReason} ({e.revokedByName})</div>}</td>
                  <td>{e.authorisedByName}</td>
                  <td><Badge tone={e.status === 'ACTIVE' ? 'warn' : 'neutral'}>{e.status}</Badge></td>
                  {canGrantException && (
                    <td>{e.status === 'ACTIVE' && <button className="link" onClick={() => setDialog({ kind: 'revoke', ex: e })}>Revoke</button>}</td>
                  )}
                </tr>
              ))}
              {exceptions.length === 0 && <tr><td colSpan={6} className="empty">No exceptions</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      {can('USER_MANAGE') && (
        <section className="card section">
          <div className="section-head">
            <h3>Customer logins</h3>
            <Link to={`/users?newCustomer=${c.id}`} className="button"><i className="bi bi-plus-lg" />Create login</Link>
          </div>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Username</th><th>Name</th><th>Last login</th><th>Status</th></tr></thead>
              <tbody>
                {logins.map((u) => (
                  <tr key={u.id}>
                    <td className="mono">{u.username}</td><td>{u.fullName}</td>
                    <td>{u.lastLoginAt ? formatDateTime(u.lastLoginAt) : 'Never'}</td>
                    <td><Badge tone={u.isActive ? 'ok' : 'neutral'}>{u.isActive ? 'Active' : 'Inactive'}</Badge></td>
                  </tr>
                ))}
                {logins.length === 0 && <tr><td colSpan={4} className="empty">No logins for this customer yet</td></tr>}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {dialog?.kind === 'edit' && (
        <Modal title="Edit customer" onClose={close} wide>
          <CustomerForm initial={c} mode="edit" busy={edit.busy} error={edit.error}
            onSubmit={async (profile) => {
              const r = await edit.run(() => api<Detail>(`/customers/${id}`, {
                method: 'PUT', body: JSON.stringify({ profile, version: c.version }),
              }));
              if (r) { close(); load(); }
            }} />
        </Modal>
      )}
      {dialog?.kind === 'status' && <StatusDialog current={c.status} id={id} onClose={close} onDone={load} />}
      {dialog?.kind === 'limit' && <LimitDialog current={c.creditLimit} id={id} onClose={close} onDone={load} />}
      {dialog?.kind === 'exception' && <ExceptionDialog id={id} onClose={close} onDone={load} />}
      {dialog?.kind === 'revoke' && (
        <ReasonModal title={`Revoke ${formatINR(dialog.ex.extraLimit)} exception`} confirmLabel="Revoke" danger onClose={close}
          onConfirm={(reason) => api(`/credit-exceptions/${dialog.ex.id}/revoke`, { method: 'POST', body: JSON.stringify({ reason }) }).then(load)} />
      )}
      {dialog?.kind === 'vehicle' && <VehicleDialog customerId={id} vehicle={dialog.vehicle} onClose={close} onDone={load} />}
      {dialog?.kind === 'vehicleActive' && (
        <ReasonModal title={`${dialog.vehicle.isActive ? 'Deactivate' : 'Reactivate'} ${dialog.vehicle.vehicleNumber}`}
          confirmLabel={dialog.vehicle.isActive ? 'Deactivate' : 'Reactivate'} danger={dialog.vehicle.isActive} onClose={close}
          onConfirm={(reason) => api(`/vehicles/${dialog.vehicle.id}/status`, {
            method: 'PATCH', body: JSON.stringify({ active: !dialog.vehicle.isActive, reason }),
          }).then(load)} />
      )}
    </>
  );
}

function Tile({ label, value, sub, bad }: { label: string; value: string; sub?: string; bad?: boolean }) {
  return (
    <div className="card tile">
      <div className="muted">{label}</div>
      <div className={`value${bad ? ' bad-text' : ''}`}>{value}</div>
      {sub && <div className="small muted">{sub}</div>}
    </div>
  );
}

function StatusDialog({ id, current, onClose, onDone }: { id: number; current: string; onClose: () => void; onDone: () => void }) {
  const [status, setStatus] = useState(current);
  return (
    <ReasonModal title="Change customer status" confirmLabel="Update status" onClose={onClose}
      onConfirm={(reason) => api(`/customers/${id}/status`, { method: 'PATCH', body: JSON.stringify({ status, reason }) }).then(onDone)}>
      <Field label="Status">
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="ACTIVE">Active</option>
          <option value="SUSPENDED">Suspended (no new indents)</option>
          <option value="CLOSED">Closed</option>
        </select>
      </Field>
    </ReasonModal>
  );
}

function LimitDialog({ id, current, onClose, onDone }: { id: number; current: number; onClose: () => void; onDone: () => void }) {
  const [limit, setLimit] = useState(String(current));
  return (
    <ReasonModal title="Change credit limit" confirmLabel="Save new limit" onClose={onClose}
      onConfirm={(reason) => api(`/customers/${id}/credit-limit`, {
        method: 'PUT', body: JSON.stringify({ newLimit: Number(limit), reason }),
      }).then(onDone)}>
      <p className="muted">Current limit: {formatINR(current)}</p>
      <Field label="New credit limit (₹)">
        <input type="number" min={0} step="0.01" value={limit} onChange={(e) => setLimit(e.target.value)} required />
      </Field>
    </ReasonModal>
  );
}

function ExceptionDialog({ id, onClose, onDone }: { id: number; onClose: () => void; onDone: () => void }) {
  const [extra, setExtra] = useState('');
  const [until, setUntil] = useState('');
  return (
    <ReasonModal title="Authorise temporary limit exception" confirmLabel="Authorise" onClose={onClose}
      onConfirm={(reason) => api(`/customers/${id}/credit-exceptions`, {
        method: 'POST',
        body: JSON.stringify({ extraLimit: Number(extra), validUntil: new Date(until).toISOString(), reason }),
      }).then(onDone)}>
      <p className="muted">Adds headroom on top of the credit limit until the end date (max 90 days). Starts now.</p>
      <Field label="Extra limit (₹)"><input type="number" min={1} step="0.01" value={extra} onChange={(e) => setExtra(e.target.value)} required /></Field>
      <Field label="Valid until"><input type="datetime-local" value={until} onChange={(e) => setUntil(e.target.value)} required /></Field>
    </ReasonModal>
  );
}

function VehicleDialog({ customerId, vehicle, onClose, onDone }: {
  customerId: number; vehicle?: Vehicle; onClose: () => void; onDone: () => void;
}) {
  const [f, setF] = useState({
    vehicleNumber: vehicle?.vehicleNumber ?? '', vehicleType: vehicle?.vehicleType ?? '',
    defaultFuel: vehicle?.defaultFuel ?? '', description: vehicle?.description ?? '',
  });
  const { busy, error, run } = useAction();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const body = JSON.stringify({
      vehicleNumber: f.vehicleNumber, vehicleType: nullIfBlank(f.vehicleType),
      defaultFuel: nullIfBlank(f.defaultFuel), description: nullIfBlank(f.description),
    });
    const ok = await run(() => vehicle
      ? api(`/vehicles/${vehicle.id}`, { method: 'PUT', body })
      : api(`/customers/${customerId}/vehicles`, { method: 'POST', body }));
    if (ok !== undefined) { onClose(); onDone(); }
  };
  return (
    <Modal title={vehicle ? `Edit ${vehicle.vehicleNumber}` : 'Add vehicle'} onClose={onClose}>
      <form onSubmit={submit}>
        <Field label="Vehicle number *" hint="Spaces and dashes are removed, e.g. KA01AB1234">
          <input value={f.vehicleNumber} onChange={(e) => setF({ ...f, vehicleNumber: e.target.value.toUpperCase() })} required />
        </Field>
        <Field label="Type">
          <select value={f.vehicleType} onChange={(e) => setF({ ...f, vehicleType: e.target.value })}>
            <option value="">—</option>
            {['TRUCK', 'BUS', 'CAR', 'TRACTOR', 'TEMPO', 'JCB', 'GENERATOR', 'OTHER'].map((t) => <option key={t}>{t}</option>)}
          </select>
        </Field>
        <Field label="Default fuel">
          <select value={f.defaultFuel} onChange={(e) => setF({ ...f, defaultFuel: e.target.value })}>
            <option value="">—</option><option value="DIESEL">Diesel</option><option value="PETROL">Petrol</option>
          </select>
        </Field>
        <Field label="Notes"><input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
        <ErrorBox error={error} />
        <button className="primary" disabled={busy}>{busy ? 'Saving…' : 'Save vehicle'}</button>
      </form>
    </Modal>
  );
}
