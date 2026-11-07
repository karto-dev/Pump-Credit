import { useEffect, useState, type ReactNode } from 'react';

export function Modal({ title, onClose, children, wide }: {
  title: string; onClose: () => void; children: ReactNode; wide?: boolean;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    document.body.classList.add('modal-open');
    return () => { window.removeEventListener('keydown', onKey); document.body.classList.remove('modal-open'); };
  }, [onClose]);
  return (
    <>
      <div className="modal-backdrop fade show" />
      <div className="modal d-block" role="dialog" aria-modal="true" aria-label={title}
           onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
        <div className={`modal-dialog${wide ? ' modal-lg' : ''}`}>
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title">{title}</h5>
              <button type="button" className="btn-close" onClick={onClose} aria-label="Close" />
            </div>
            <div className="modal-body">{children}</div>
          </div>
        </div>
      </div>
    </>
  );
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label>
      {label}
      {children}
      {hint && <span className="hint">{hint}</span>}
    </label>
  );
}

export function ErrorBox({ error }: { error: string | null }) {
  return error ? <div className="error">{error}</div> : null;
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'ok' | 'warn' | 'bad' | 'neutral' }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}

/** Runs an async action, tracking busy state and turning thrown errors into a message. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async <T,>(fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(null);
    try {
      return await fn();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Something went wrong');
      return undefined;
    } finally {
      setBusy(false);
    }
  };
  return { busy, error, setError, run };
}

/** Empty inputs become null so optional, pattern-validated fields pass server validation. */
export const nullIfBlank = (s: string | null | undefined) => (s == null || s.trim() === '' ? null : s.trim());
