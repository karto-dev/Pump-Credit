import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, formatDateTime, type Role } from '../../api/client';
import type { CustomerRow, PermissionState, UserRow } from '../../api/types';
import { useAuth } from '../../auth';
import ReasonModal from '../../components/ReasonModal';
import { Badge, ErrorBox, Field, Modal, nullIfBlank, useAction } from '../../components/ui';

const ROLES: Role[] = ['CUSTOMER', 'CASHIER', 'MANAGER', 'ADMIN'];

type Dialog =
  | { kind: 'form'; user?: UserRow; customerId?: number }
  | { kind: 'status'; user: UserRow }
  | { kind: 'reset'; user: UserRow }
  | { kind: 'perms'; user: UserRow }
  | { kind: 'secret'; username: string; password: string }
  | null;

export default function Users() {
  const { user: me, can } = useAuth();
  const [params, setParams] = useSearchParams();
  const [rows, setRows] = useState<UserRow[]>([]);
  const [q, setQ] = useState('');
  const [role, setRole] = useState('');
  const [dialog, setDialog] = useState<Dialog>(null);
  const list = useAction();

  const load = useCallback(() => {
    const p = new URLSearchParams();
    if (q) p.set('q', q);
    if (role) p.set('role', role);
    list.run(() => api<UserRow[]>(`/users?${p}`).then(setRows));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, role]);

  useEffect(() => {
    const t = setTimeout(load, 250);
    return () => clearTimeout(t);
  }, [load]);

  // Deep link from a customer page: /users?newCustomer=12
  useEffect(() => {
    const cid = params.get('newCustomer');
    if (cid) {
      setDialog({ kind: 'form', customerId: Number(cid) });
      setParams({}, { replace: true });
    }
  }, [params, setParams]);

  const close = () => setDialog(null);
  // Non-admins with USER_MANAGE cannot touch admin accounts (enforced server-side too).
  const canTouch = (u: UserRow) => u.roleCode !== 'ADMIN' || me?.role === 'ADMIN';

  return (
    <>
      <div className="page-head">
        <h2>Users</h2>
        <button className="primary auto" onClick={() => setDialog({ kind: 'form' })}><i className="bi bi-plus-lg" />New user</button>
      </div>
      <div className="filters">
        <input placeholder="Search username, name or phone" value={q} onChange={(e) => setQ(e.target.value)} />
        <select value={role} onChange={(e) => setRole(e.target.value)}>
          <option value="">All roles</option>
          {ROLES.map((r) => <option key={r}>{r}</option>)}
        </select>
      </div>
      <ErrorBox error={list.error} />

      <div className="table-wrap card flush">
        <table>
          <thead><tr><th>User</th><th>Role</th><th>Customer</th><th>Last login</th><th>Status</th><th /></tr></thead>
          <tbody>
            {rows.map((u) => (
              <tr key={u.id} className={u.isActive ? '' : 'inactive'}>
                <td>{u.fullName}<div className="muted small mono">{u.username}{u.phone ? ` · ${u.phone}` : ''}</div></td>
                <td><Badge>{u.roleCode}</Badge></td>
                <td>{u.customerId ? <Link to={`/customers/${u.customerId}`}>{u.customerName}</Link> : '—'}</td>
                <td className="small">{u.lastLoginAt ? formatDateTime(u.lastLoginAt) : 'Never'}</td>
                <td>
                  <Badge tone={u.isActive ? 'ok' : 'neutral'}>{u.isActive ? 'Active' : 'Inactive'}</Badge>
                  {u.lockedUntil && new Date(u.lockedUntil) > new Date() && <Badge tone="bad">Locked</Badge>}
                  {u.mustChangePassword && <Badge tone="warn">Temp password</Badge>}
                </td>
                <td className="row-actions">
                  {canTouch(u) && <>
                    <button className="link" onClick={() => setDialog({ kind: 'form', user: u })}>Edit</button>
                    {u.id !== me?.id && (
                      <button className="link" onClick={() => setDialog({ kind: 'status', user: u })}>
                        {u.isActive ? 'Deactivate' : 'Reactivate'}
                      </button>
                    )}
                    <button className="link" onClick={() => setDialog({ kind: 'reset', user: u })}>Reset password</button>
                    {can('PERMISSION_MANAGE') && u.id !== me?.id && u.roleCode !== 'ADMIN' && (
                      <button className="link" onClick={() => setDialog({ kind: 'perms', user: u })}>Permissions</button>
                    )}
                  </>}
                </td>
              </tr>
            ))}
            {!list.busy && rows.length === 0 && <tr><td colSpan={6} className="empty">No users found</td></tr>}
          </tbody>
        </table>
      </div>

      {dialog?.kind === 'form' && (
        <UserForm user={dialog.user} presetCustomerId={dialog.customerId} isAdmin={me?.role === 'ADMIN'} onClose={close}
          onSaved={(created) => {
            load();
            setDialog(created ? { kind: 'secret', username: created.username, password: created.password } : null);
          }} />
      )}
      {dialog?.kind === 'status' && (
        <ReasonModal title={`${dialog.user.isActive ? 'Deactivate' : 'Reactivate'} ${dialog.user.username}`}
          confirmLabel={dialog.user.isActive ? 'Deactivate' : 'Reactivate'} danger={dialog.user.isActive} onClose={close}
          onConfirm={(reason) => api(`/users/${dialog.user.id}/status`, {
            method: 'PATCH', body: JSON.stringify({ active: !dialog.user.isActive, reason }),
          }).then(load)}>
          {dialog.user.isActive && <p className="muted">The user is signed out of all devices immediately.</p>}
        </ReasonModal>
      )}
      {dialog?.kind === 'reset' && (
        <ReasonModal title={`Reset password for ${dialog.user.username}`} confirmLabel="Generate temporary password" onClose={close}
          onConfirm={async (reason) => {
            const r = await api<{ temporaryPassword: string }>(`/users/${dialog.user.id}/reset-password`, {
              method: 'POST', body: JSON.stringify({ reason }),
            });
            load();
            // Defer so ReasonModal's own onClose doesn't wipe the secret dialog.
            setTimeout(() => setDialog({ kind: 'secret', username: dialog.user.username, password: r.temporaryPassword }));
          }}>
          <p className="muted">Signs the user out everywhere and unlocks the account. They must set a new password at next login.</p>
        </ReasonModal>
      )}
      {dialog?.kind === 'perms' && <UserPermissions user={dialog.user} onClose={close} />}
      {dialog?.kind === 'secret' && (
        <Modal title="Temporary password" onClose={close}>
          <p>Give this to <b>{dialog.username}</b>. It is shown only once; they will be asked to change it at first login.</p>
          <div className="secret mono">{dialog.password}</div>
          <button className="primary" onClick={() => navigator.clipboard?.writeText(dialog.password)}>Copy</button>
        </Modal>
      )}
    </>
  );
}

function UserForm({ user, presetCustomerId, isAdmin, onClose, onSaved }: {
  user?: UserRow; presetCustomerId?: number; isAdmin: boolean; onClose: () => void;
  onSaved: (created?: { username: string; password: string }) => void;
}) {
  const [f, setF] = useState({
    username: user?.username ?? '', fullName: user?.fullName ?? '', email: user?.email ?? '', phone: user?.phone ?? '',
    roleCode: (user?.roleCode ?? (presetCustomerId ? 'CUSTOMER' : 'CASHIER')) as Role,
    customerId: user?.customerId ?? presetCustomerId ?? null, reason: '',
  });
  const [customers, setCustomers] = useState<CustomerRow[]>([]);
  const { busy, error, run } = useAction();
  const roleChanged = !!user && user.roleCode !== f.roleCode;

  useEffect(() => {
    if (f.roleCode === 'CUSTOMER' && customers.length === 0) {
      api<CustomerRow[]>('/customers').then(setCustomers).catch(() => undefined);
    }
  }, [f.roleCode, customers.length]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const base = {
      fullName: f.fullName.trim(), email: nullIfBlank(f.email), phone: nullIfBlank(f.phone), roleCode: f.roleCode,
      customerId: f.roleCode === 'CUSTOMER' ? f.customerId : null,
    };
    if (user) {
      const r = await run(() => api(`/users/${user.id}`, { method: 'PUT', body: JSON.stringify({ ...base, reason: nullIfBlank(f.reason) }) }));
      if (r) onSaved();
    } else {
      const r = await run(() => api<{ temporaryPassword: string }>('/users', {
        method: 'POST', body: JSON.stringify({ ...base, username: f.username.trim() }),
      }));
      if (r) onSaved({ username: f.username.trim(), password: r.temporaryPassword });
    }
  };

  return (
    <Modal title={user ? `Edit ${user.username}` : 'New user'} onClose={onClose}>
      <form onSubmit={submit}>
        <Field label="Username *">
          <input value={f.username} onChange={(e) => setF({ ...f, username: e.target.value })} disabled={!!user} required autoComplete="off" />
        </Field>
        <Field label="Full name *"><input value={f.fullName} onChange={(e) => setF({ ...f, fullName: e.target.value })} required /></Field>
        <div className="grid2">
          <Field label="Mobile"><input value={f.phone} onChange={(e) => setF({ ...f, phone: e.target.value })} inputMode="numeric" maxLength={10} /></Field>
          <Field label="Email"><input type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field>
        </div>
        <Field label="Role *">
          <select value={f.roleCode} onChange={(e) => setF({ ...f, roleCode: e.target.value as Role })}>
            {ROLES.filter((r) => r !== 'ADMIN' || isAdmin).map((r) => <option key={r}>{r}</option>)}
          </select>
        </Field>
        {f.roleCode === 'CUSTOMER' && (
          <Field label="Customer account *" hint="This login will see only this customer's data">
            <select value={f.customerId ?? ''} onChange={(e) => setF({ ...f, customerId: e.target.value ? Number(e.target.value) : null })} required>
              <option value="">Select customer…</option>
              {customers.map((c) => <option key={c.id} value={c.id}>{c.name} ({c.customerCode})</option>)}
            </select>
          </Field>
        )}
        {roleChanged && (
          <Field label="Reason for role change *">
            <input value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} required />
          </Field>
        )}
        {!user && <p className="muted small">A temporary password will be generated and shown once.</p>}
        <ErrorBox error={error} />
        <button className="primary" disabled={busy}>{busy ? 'Saving…' : user ? 'Save changes' : 'Create user'}</button>
      </form>
    </Modal>
  );
}

function UserPermissions({ user, onClose }: { user: UserRow; onClose: () => void }) {
  const [perms, setPerms] = useState<PermissionState[]>([]);
  const [overrides, setOverrides] = useState<Record<string, boolean | null>>({});
  const [reason, setReason] = useState('');
  const load = useAction();
  const save = useAction();

  useEffect(() => {
    load.run(() => api<PermissionState[]>(`/users/${user.id}/permissions`).then((p) => {
      setPerms(p);
      setOverrides(Object.fromEntries(p.map((x) => [x.code, x.override])));
    }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user.id]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const body = {
      overrides: Object.entries(overrides).filter(([, g]) => g !== null).map(([code, granted]) => ({ code, granted })),
      reason,
    };
    const r = await save.run(() => api(`/users/${user.id}/permission-overrides`, { method: 'PUT', body: JSON.stringify(body) }));
    if (r) onClose();
  };

  const modules = [...new Set(perms.map((p) => p.module))];
  return (
    <Modal title={`Permissions · ${user.fullName} (${user.roleCode})`} onClose={onClose} wide>
      <p className="muted small">"Role default" follows the {user.roleCode} role. Grant or revoke to override it for this user only.</p>
      <ErrorBox error={load.error} />
      <form onSubmit={submit}>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Permission</th><th>Role default</th><th>This user</th></tr></thead>
            <tbody>
              {modules.map((m) => [
                <tr key={m} className="group"><td colSpan={3}>{m}</td></tr>,
                ...perms.filter((p) => p.module === m).map((p) => {
                  const o = overrides[p.code];
                  const effective = o ?? p.fromRole;
                  return (
                    <tr key={p.code}>
                      <td>{p.description}<div className="muted small mono">{p.code}</div></td>
                      <td>{p.fromRole ? <i className="bi bi-check-lg ok-text" /> : '—'}</td>
                      <td>
                        <select value={o === null || o === undefined ? '' : String(o)}
                                onChange={(e) => setOverrides({ ...overrides, [p.code]: e.target.value === '' ? null : e.target.value === 'true' })}
                                className={o === null || o === undefined ? '' : effective ? 'ok-text' : 'bad-text'}>
                          <option value="">Role default ({p.fromRole ? 'allowed' : 'denied'})</option>
                          <option value="true">Grant</option>
                          <option value="false">Revoke</option>
                        </select>
                      </td>
                    </tr>
                  );
                }),
              ])}
            </tbody>
          </table>
        </div>
        <Field label="Reason *"><input value={reason} onChange={(e) => setReason(e.target.value)} required /></Field>
        <ErrorBox error={save.error} />
        <button className="primary" disabled={save.busy || !reason.trim()}>{save.busy ? 'Saving…' : 'Save permissions'}</button>
      </form>
    </Modal>
  );
}
