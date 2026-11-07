import { useState, type FormEvent, type ReactNode } from 'react';
import { ErrorBox, Field, Modal, useAction } from './ui';

/** Confirmation dialog that requires a reason - every sensitive change is audited with one. */
export default function ReasonModal({ title, confirmLabel, danger, children, onConfirm, onClose }: {
  title: string;
  confirmLabel: string;
  danger?: boolean;
  children?: ReactNode;
  onConfirm: (reason: string) => Promise<unknown>;
  onClose: () => void;
}) {
  const [reason, setReason] = useState('');
  const { busy, error, run } = useAction();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const ok = await run(async () => { await onConfirm(reason.trim()); return true; });
    if (ok) onClose();
  };
  return (
    <Modal title={title} onClose={onClose}>
      <form onSubmit={submit}>
        {children}
        <Field label="Reason *" hint="Recorded in the audit log">
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} required maxLength={500} />
        </Field>
        <ErrorBox error={error} />
        <button className={danger ? 'primary danger' : 'primary'} disabled={busy || !reason.trim()}>
          {busy ? 'Saving…' : confirmLabel}
        </button>
      </form>
    </Modal>
  );
}
