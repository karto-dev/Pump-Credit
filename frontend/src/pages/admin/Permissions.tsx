import { useEffect, useState } from 'react';
import { api, type Role } from '../../api/client';
import type { PermissionMatrix } from '../../api/types';
import ReasonModal from '../../components/ReasonModal';
import { ErrorBox, useAction } from '../../components/ui';

// Exceptions to credit limits stay admin-only by policy, so no other role can be ticked.
const ADMIN_ONLY = new Set(['CREDIT_LIMIT_EXCEPTION']);

export default function Permissions() {
  const [matrix, setMatrix] = useState<PermissionMatrix | null>(null);
  const [draft, setDraft] = useState<Record<string, Set<string>>>({});
  const [saving, setSaving] = useState<Role | null>(null);
  const load = useAction();

  const apply = (m: PermissionMatrix) => {
    setMatrix(m);
    setDraft(Object.fromEntries(Object.entries(m.grants).map(([r, p]) => [r, new Set(p)])));
  };

  useEffect(() => {
    load.run(() => api<PermissionMatrix>('/admin/permission-matrix').then(apply));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!matrix) return <ErrorBox error={load.error} />;

  const editableRoles = matrix.roles.filter((r) => r.code !== 'ADMIN');
  const dirty = (r: Role) => {
    const a = new Set(matrix.grants[r]);
    const b = draft[r];
    return a.size !== b.size || [...a].some((x) => !b.has(x));
  };
  const toggle = (r: Role, code: string) => {
    const next = new Set(draft[r]);
    if (next.has(code)) next.delete(code); else next.add(code);
    setDraft({ ...draft, [r]: next });
  };
  const modules = [...new Set(matrix.permissions.map((p) => p.module))];

  return (
    <>
      <div className="page-head">
        <div>
          <h2>Role permissions</h2>
          <p className="muted">Default access for each role. Override individual users from the Users page. Admin always has every permission.</p>
        </div>
      </div>

      <div className="table-wrap card flush">
        <table className="matrix">
          <thead>
            <tr>
              <th>Permission</th>
              {editableRoles.map((r) => (
                <th key={r.code} className="center">
                  {r.name}
                  {dirty(r.code) && (
                    <div><button className="primary tiny" onClick={() => setSaving(r.code)}>Save</button></div>
                  )}
                </th>
              ))}
              <th className="center">Admin</th>
            </tr>
          </thead>
          <tbody>
            {modules.map((m) => [
              <tr key={m} className="group"><td colSpan={editableRoles.length + 2}>{m}</td></tr>,
              ...matrix.permissions.filter((p) => p.module === m).map((p) => (
                <tr key={p.code}>
                  <td>{p.description}<div className="muted small mono">{p.code}</div></td>
                  {editableRoles.map((r) => {
                    const changed = matrix.grants[r.code].includes(p.code) !== draft[r.code].has(p.code);
                    return (
                      <td key={r.code} className={`center${changed ? ' changed' : ''}`}>
                        <input type="checkbox" checked={draft[r.code].has(p.code)} disabled={ADMIN_ONLY.has(p.code)}
                               onChange={() => toggle(r.code, p.code)} aria-label={`${r.name}: ${p.code}`} />
                      </td>
                    );
                  })}
                  <td className="center"><input type="checkbox" checked disabled aria-label={`Admin: ${p.code}`} /></td>
                </tr>
              )),
            ])}
          </tbody>
        </table>
      </div>

      {saving && (
        <ReasonModal title={`Save ${saving} permissions`} confirmLabel="Save" onClose={() => setSaving(null)}
          onConfirm={(reason) => api<PermissionMatrix>(`/admin/roles/${saving}/permissions`, {
            method: 'PUT', body: JSON.stringify({ permissions: [...draft[saving]], reason }),
          }).then(apply)}>
          <p className="muted">Applies immediately to every {saving.toLowerCase()} user, including those signed in now.</p>
        </ReasonModal>
      )}
    </>
  );
}
