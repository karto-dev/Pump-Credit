import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, apiDownload, formatDateTime } from '../../api/client';
import { useAuth } from '../../auth';
import ReasonModal from '../../components/ReasonModal';
import { Badge, ErrorBox, Field, Modal, useAction } from '../../components/ui';

const TABS = [
  { key: 'settings', label: 'Settings', perm: 'SETTINGS_MANAGE' },
  { key: 'notifications', label: 'Notifications', perm: 'NOTIFICATION_CONFIG' },
  { key: 'backups', label: 'Backups', perm: 'BACKUP_MANAGE' },
  { key: 'audit', label: 'Audit log', perm: 'AUDIT_VIEW' },
] as const;

export const ADMIN_PERMS = TABS.map((t) => t.perm);

export default function Admin() {
  const { can } = useAuth();
  const tabs = TABS.filter((t) => can(t.perm));
  const [params, setParams] = useSearchParams();
  const tab = tabs.find((t) => t.key === params.get('t'))?.key ?? tabs[0]?.key;
  if (!tab) return <div className="card empty">Nothing to administer with your permissions</div>;
  return (
    <>
      <div className="page-head"><h2>Administration</h2></div>
      <div className="tabs">
        {tabs.map((t) => (
          <button key={t.key} className={tab === t.key ? 'selected' : ''} onClick={() => setParams({ t: t.key })}>{t.label}</button>
        ))}
      </div>
      {tab === 'settings' && <Settings />}
      {tab === 'notifications' && <Notifications />}
      {tab === 'backups' && <Backups />}
      {tab === 'audit' && <AuditLog />}
    </>
  );
}

// ------------------------------------------------------------------ settings

interface Setting { key: string; value: string; description: string | null; updatedByName: string | null; updatedAt: string }

const CHOICES: Record<string, string[]> = {
  OVER_LIMIT_INDENT_ACTION: ['HOLD', 'BLOCK'],
  OVERDUE_INDENT_ACTION: ['ALLOW', 'HOLD', 'BLOCK'],
};

function Settings() {
  const [rows, setRows] = useState<Setting[]>([]);
  const [editing, setEditing] = useState<Setting | null>(null);
  const [value, setValue] = useState('');
  const load = useAction();
  const reload = useCallback(() => { load.run(() => api<Setting[]>('/settings').then(setRows)); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(reload, [reload]);
  const remind = useAction();
  const [reminded, setReminded] = useState<number | null>(null);

  return (
    <>
      <ErrorBox error={load.error} />
      <div className="table-wrap card flush">
        <table>
          <thead><tr><th>Setting</th><th>Value</th><th>Last changed</th><th /></tr></thead>
          <tbody>
            {rows.map((s) => (
              <tr key={s.key}>
                <td><span className="mono small">{s.key}</span><div className="small muted">{s.description}</div></td>
                <td className="pre"><b>{s.value === '' ? <span className="muted">(blank)</span> : s.value}</b></td>
                <td className="small muted">{s.updatedByName ? `${s.updatedByName}, ` : ''}{formatDateTime(s.updatedAt)}</td>
                <td><button className="link" onClick={() => { setEditing(s); setValue(s.value); }}>Change</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="card section-gap">
        <div className="section-head">
          <div><b>Outstanding reminders</b><div className="small muted">Sent daily at 10:00 to customers who owe money, at most once every PAYMENT_REMINDER_DAYS.</div></div>
          <button onClick={() => remind.run(() => api<{ customers: number }>('/jobs/reminders', { method: 'POST' }).then((r) => setReminded(r.customers)))}
                  disabled={remind.busy}>{remind.busy ? 'Sending…' : 'Send due reminders now'}</button>
        </div>
        {reminded != null && <div className="small">Reminded {reminded} customer(s).</div>}
        <ErrorBox error={remind.error} />
      </div>
      {editing && (
        <ReasonModal title={`Change ${editing.key}`} confirmLabel="Save setting" onClose={() => setEditing(null)}
          onConfirm={(reason) => api(`/settings/${editing.key}`, { method: 'PUT', body: JSON.stringify({ value, reason }) }).then(reload)}>
          <p className="small muted">{editing.description}</p>
          <Field label="Value">
            {CHOICES[editing.key]
              ? <select value={value} onChange={(e) => setValue(e.target.value)}>{CHOICES[editing.key].map((c) => <option key={c}>{c}</option>)}</select>
              : editing.key === 'PUMP_BANK_DETAILS'
                ? <textarea value={value} onChange={(e) => setValue(e.target.value)} rows={4} maxLength={500} />
                : <input value={value} onChange={(e) => setValue(e.target.value)} maxLength={500} />}
          </Field>
        </ReasonModal>
      )}
    </>
  );
}

// ------------------------------------------------------------------ notifications

interface EventSetting {
  eventType: string; description: string; inAppEnabled: boolean; smsEnabled: boolean; whatsappEnabled: boolean;
  titleTemplate: string; bodyTemplate: string; updatedByName: string | null; updatedAt: string;
}
interface Delivery {
  id: number; channel: string; destination: string; status: 'QUEUED' | 'SENT' | 'FAILED'; attempts: number;
  lastError: string | null; createdAt: string; sentAt: string | null; eventType: string; title: string; recipientName: string;
}

function Notifications() {
  const [events, setEvents] = useState<EventSetting[]>([]);
  const [provider, setProvider] = useState('');
  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [editing, setEditing] = useState<EventSetting | null>(null);
  const load = useAction();
  const save = useAction();

  const reload = useCallback(() => {
    load.run(async () => {
      const r = await api<{ provider: string; events: EventSetting[] }>('/notification-settings');
      setEvents(r.events); setProvider(r.provider);
      setDeliveries(await api<Delivery[]>('/notification-settings/deliveries'));
    });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(reload, [reload]);

  const toggle = (e: EventSetting, field: 'inAppEnabled' | 'smsEnabled' | 'whatsappEnabled') =>
    save.run(() => api<EventSetting>(`/notification-settings/${e.eventType}`, {
      method: 'PUT', body: JSON.stringify({ ...e, [field]: !e[field] }),
    }).then((u) => setEvents((rows) => rows.map((x) => (x.eventType === u.eventType ? u : x)))));

  return (
    <>
      <p className="notice small">
        SMS / WhatsApp provider: <b>{provider}</b>.
        {provider === 'LOG' && ' Messages are written to the server log only. Set NOTIFY_PROVIDER=WEBHOOK and NOTIFY_WEBHOOK_URL to send real messages.'}
      </p>
      <ErrorBox error={load.error ?? save.error} />
      <div className="table-wrap card flush">
        <table>
          <thead><tr><th>Event</th><th>Message</th><th className="center">In app</th><th className="center">SMS</th><th className="center">WhatsApp</th><th /></tr></thead>
          <tbody>
            {events.map((e) => (
              <tr key={e.eventType}>
                <td><b className="small">{e.description}</b><div className="mono small muted">{e.eventType}</div></td>
                <td className="small"><b>{e.titleTemplate}</b><div className="muted">{e.bodyTemplate}</div></td>
                {(['inAppEnabled', 'smsEnabled', 'whatsappEnabled'] as const).map((f) => (
                  <td key={f} className="center"><input type="checkbox" checked={e[f]} disabled={save.busy} onChange={() => toggle(e, f)} /></td>
                ))}
                <td><button className="link" onClick={() => setEditing(e)}>Edit text</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="section-head section-gap"><h3>Recent SMS / WhatsApp</h3><button className="link" onClick={reload}><i className="bi bi-arrow-clockwise" /> Refresh</button></div>
      <div className="table-wrap card flush">
        <table>
          <thead><tr><th>Queued</th><th>To</th><th>Message</th><th>Status</th><th /></tr></thead>
          <tbody>
            {deliveries.map((d) => (
              <tr key={d.id}>
                <td className="small">{formatDateTime(d.createdAt)}</td>
                <td className="small">{d.recipientName}<div className="mono muted">{d.channel} {d.destination}</div></td>
                <td className="small">{d.title}</td>
                <td>
                  <Badge tone={d.status === 'SENT' ? 'ok' : d.status === 'FAILED' ? 'bad' : 'warn'}>{d.status}</Badge>
                  {d.lastError && <div className="small bad-text">{d.lastError}</div>}
                </td>
                <td>{d.status === 'FAILED' && <button className="link" onClick={() => save.run(() => api(`/notification-settings/deliveries/${d.id}/retry`, { method: 'POST' }).then(reload))}>Retry</button>}</td>
              </tr>
            ))}
            {deliveries.length === 0 && <tr><td colSpan={5} className="empty">No SMS or WhatsApp messages yet</td></tr>}
          </tbody>
        </table>
      </div>
      {editing && <EditTemplate event={editing} onClose={() => setEditing(null)}
        onSaved={(u) => { setEvents((rows) => rows.map((x) => (x.eventType === u.eventType ? u : x))); setEditing(null); }} />}
    </>
  );
}

function EditTemplate({ event, onClose, onSaved }: { event: EventSetting; onClose: () => void; onSaved: (e: EventSetting) => void }) {
  const [title, setTitle] = useState(event.titleTemplate);
  const [body, setBody] = useState(event.bodyTemplate);
  const save = useAction();
  const vars = Array.from(new Set(`${event.titleTemplate} ${event.bodyTemplate}`.match(/\{\w+}/g) ?? []));
  return (
    <Modal title={event.description} onClose={onClose}>
      <form onSubmit={(e) => { e.preventDefault(); save.run(() => api<EventSetting>(`/notification-settings/${event.eventType}`, {
        method: 'PUT', body: JSON.stringify({ ...event, titleTemplate: title, bodyTemplate: body }),
      }).then(onSaved)); }}>
        <Field label="Title"><input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} required /></Field>
        <Field label="Message" hint={vars.length ? `Placeholders: ${vars.join(' ')}` : undefined}>
          <textarea value={body} onChange={(e) => setBody(e.target.value)} rows={3} maxLength={1000} required />
        </Field>
        <ErrorBox error={save.error} />
        <button className="primary" disabled={save.busy}>{save.busy ? 'Saving…' : 'Save'}</button>
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------ backups

interface BackupRow { id: number; startedAt: string; finishedAt: string | null; status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  location: string | null; sizeBytes: number | null; triggeredByName: string | null; error: string | null }

function Backups() {
  const [rows, setRows] = useState<BackupRow[]>([]);
  const load = useAction();
  const run = useAction();
  const reload = useCallback(() => { load.run(() => api<BackupRow[]>('/backups').then(setRows)); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(reload, [reload]);
  const size = (b: number | null) => (b == null ? '' : b > 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${Math.ceil(b / 1024)} KB`);

  return (
    <>
      <div className="section-head">
        <p className="small muted">Nightly at 02:30 when BACKUP_ENABLED=true. Local copies are kept for BACKUP_RETENTION_DAYS; on AWS each backup is also copied to S3.</p>
        <button className="primary auto" disabled={run.busy} onClick={() => run.run(() => api('/backups', { method: 'POST' }).then(reload))}>
          {run.busy ? 'Backing up…' : 'Back up now'}
        </button>
      </div>
      <ErrorBox error={load.error ?? run.error} />
      <div className="table-wrap card flush">
        <table>
          <thead><tr><th>Started</th><th>Status</th><th>Size</th><th>By</th><th>Where / error</th><th /></tr></thead>
          <tbody>
            {rows.map((b) => (
              <tr key={b.id}>
                <td className="small">{formatDateTime(b.startedAt)}</td>
                <td><Badge tone={b.status === 'SUCCESS' ? 'ok' : b.status === 'FAILED' ? 'bad' : 'warn'}>{b.status}</Badge></td>
                <td className="small">{size(b.sizeBytes)}</td>
                <td className="small">{b.triggeredByName ?? 'Scheduled'}</td>
                <td className="small">{b.error ? <span className="bad-text">{b.error}</span> : <span className="mono muted">{b.location}</span>}</td>
                <td>{b.status === 'SUCCESS' && (
                  <button className="link" onClick={() => run.run(() => apiDownload(`/backups/${b.id}/download`, `backup-${b.id}.dump`))}>Download</button>
                )}</td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={6} className="empty">No backups yet</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="small muted">Restore: <span className="mono">pg_restore --clean --no-owner -d petrol_credit &lt;file&gt;</span></p>
    </>
  );
}

// ------------------------------------------------------------------ audit log

interface AuditRow { id: number; occurredAt: string; actorName: string | null; actorRole: string | null; action: string;
  entityType: string; entityId: string | null; oldValue: string | null; newValue: string | null; reason: string | null; ipAddress: string | null }

function AuditLog() {
  const [q, setQ] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [rows, setRows] = useState<AuditRow[]>([]);
  const [open, setOpen] = useState<AuditRow | null>(null);
  const load = useAction();

  useEffect(() => {
    const p = new URLSearchParams();
    if (q) p.set('q', q);
    if (from) p.set('from', from);
    if (to) p.set('to', to);
    const t = setTimeout(() => load.run(() => api<AuditRow[]>(`/audit?${p}`).then(setRows)), 250);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, from, to]);

  const pretty = (s: string | null) => { try { return s ? JSON.stringify(JSON.parse(s), null, 2) : ''; } catch { return s ?? ''; } };

  return (
    <>
      <div className="filters">
        <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From" className="date" />
        <input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="To" className="date" />
        <input placeholder="Search action, reason or user" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>
      <ErrorBox error={load.error} />
      <div className="table-wrap card flush">
        <table>
          <thead><tr><th>When</th><th>Who</th><th>Action</th><th>Record</th><th>Reason</th></tr></thead>
          <tbody>
            {rows.map((a) => (
              <tr key={a.id} className="clickable" onClick={() => setOpen(a)}>
                <td className="small">{formatDateTime(a.occurredAt)}</td>
                <td className="small">{a.actorName ?? 'System'}<div className="muted">{a.actorRole}</div></td>
                <td className="mono small">{a.action}</td>
                <td className="small">{a.entityType} {a.entityId}</td>
                <td className="small">{a.reason}</td>
              </tr>
            ))}
            {!load.busy && rows.length === 0 && <tr><td colSpan={5} className="empty">No entries</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="small muted">Latest 500 entries matching the filters. The audit log cannot be edited or deleted.</p>
      {open && (
        <Modal title={open.action} onClose={() => setOpen(null)} wide>
          <dl className="kv">
            <dt>When</dt><dd>{formatDateTime(open.occurredAt)}</dd>
            <dt>Who</dt><dd>{open.actorName ?? 'System'} {open.actorRole && `(${open.actorRole})`}</dd>
            <dt>Record</dt><dd>{open.entityType} {open.entityId}</dd>
            {open.reason && <><dt>Reason</dt><dd>{open.reason}</dd></>}
            {open.ipAddress && <><dt>IP</dt><dd className="mono">{open.ipAddress}</dd></>}
          </dl>
          <div className="grid2 section-gap">
            <div><div className="small muted">Before</div><pre className="json">{pretty(open.oldValue)}</pre></div>
            <div><div className="small muted">After</div><pre className="json">{pretty(open.newValue)}</pre></div>
          </div>
        </Modal>
      )}
    </>
  );
}
