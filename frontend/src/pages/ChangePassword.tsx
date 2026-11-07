import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, type Me } from '../api/client';
import { useAuth } from '../auth';
import { ErrorBox, Field, useAction } from '../components/ui';

export default function ChangePassword() {
  const { user, setUser } = useAuth();
  const navigate = useNavigate();
  const { busy, error, setError, run } = useAction();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (next !== confirm) return setError('New passwords do not match');
    const me = await run(() => api<Me>('/auth/change-password', {
      method: 'POST', body: JSON.stringify({ currentPassword: current, newPassword: next }),
    }));
    if (me) {
      setUser(me);
      navigate('/', { replace: true });
    }
  };

  return (
    <div className="narrow">
      <form className="card" onSubmit={submit}>
        <h2>Change password</h2>
        {user?.mustChangePassword && (
          <p className="notice">You are using a temporary password. Set your own password to continue.</p>
        )}
        <Field label="Current password">
          <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)}
                 autoComplete="current-password" required />
        </Field>
        <Field label="New password" hint="At least 8 characters with letters and digits">
          <input type="password" value={next} onChange={(e) => setNext(e.target.value)}
                 autoComplete="new-password" required />
        </Field>
        <Field label="Confirm new password">
          <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)}
                 autoComplete="new-password" required />
        </Field>
        <ErrorBox error={error} />
        <button className="primary" disabled={busy}>{busy ? 'Saving…' : 'Change password'}</button>
      </form>
    </div>
  );
}
