import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth';

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
      navigate('/', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-wrap">
      <section className="login-hero">
        <div className="brand"><span className="brand-mark"><i className="bi bi-fuel-pump-fill" /></span>Pump Credit</div>
        <div style={{ marginTop: 56 }}>
          <h1>Credit sales and recovery, in one place.</h1>
          <p>Indents, bills, customer approvals, payments and statements for your fleet customers.</p>
          <ul>
            <li><i className="bi bi-fuel-pump" />Fill against approved indents with bill photos</li>
            <li><i className="bi bi-shield-check" />Payments count only after they are verified</li>
            <li><i className="bi bi-graph-up-arrow" />Outstanding, ageing and Excel / PDF reports</li>
          </ul>
        </div>
        <i className="bi bi-fuel-pump hero-mark" />
      </section>
      <section className="login-side">
        <form className="card login-card" onSubmit={submit}>
          <div className="brand mb-4"><span className="brand-mark"><i className="bi bi-fuel-pump-fill" /></span>Pump Credit</div>
          <h3>Welcome back</h3>
          <p className="muted mb-4">Sign in to your account</p>
          <label>
            Username
            <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required autoFocus />
          </label>
          <label>
            Password
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                   autoComplete="current-password" required />
          </label>
          {error && <div className="error">{error}</div>}
          <button className="primary mt-2" disabled={busy}>
            {busy ? <><span className="spinner-border spinner-border-sm" />Signing in…</> : <>Sign in<i className="bi bi-arrow-right" /></>}
          </button>
        </form>
      </section>
    </div>
  );
}
