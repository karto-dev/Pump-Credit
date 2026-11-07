import { useState, type FormEvent } from 'react';
import type { CustomerProfile } from '../../api/types';
import { ErrorBox, Field, nullIfBlank } from '../../components/ui';

export const EMPTY_PROFILE: CustomerProfile = {
  name: '', businessName: null, contactPerson: null, phone: '', alternatePhone: null, email: null, address: null,
  city: null, state: null, pincode: null, gstin: null, pan: null, creditPeriodDays: 30,
};

export interface CreateExtras { creditLimit: string; openingBalance: string; openingBalanceDate: string }

/** Shared by "new customer" (with credit limit + opening balance) and "edit profile" (profile only). */
export default function CustomerForm({ initial, mode, canSetLimit, busy, error, onSubmit }: {
  initial: CustomerProfile;
  mode: 'create' | 'edit';
  canSetLimit?: boolean;
  busy: boolean;
  error: string | null;
  onSubmit: (profile: CustomerProfile, extras: CreateExtras) => void;
}) {
  const [p, setP] = useState(initial);
  const [extras, setExtras] = useState<CreateExtras>({ creditLimit: '0', openingBalance: '0', openingBalanceDate: '' });
  const set = (k: keyof CustomerProfile) => (e: { target: { value: string } }) =>
    setP({ ...p, [k]: k === 'creditPeriodDays' ? Number(e.target.value) : e.target.value });
  const v = (s: string | null) => s ?? '';

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const clean: CustomerProfile = {
      ...p,
      name: p.name.trim(),
      businessName: nullIfBlank(p.businessName), contactPerson: nullIfBlank(p.contactPerson),
      alternatePhone: nullIfBlank(p.alternatePhone), email: nullIfBlank(p.email), address: nullIfBlank(p.address),
      city: nullIfBlank(p.city), state: nullIfBlank(p.state), pincode: nullIfBlank(p.pincode),
      gstin: nullIfBlank(p.gstin)?.toUpperCase() ?? null, pan: nullIfBlank(p.pan)?.toUpperCase() ?? null,
    };
    onSubmit(clean, extras);
  };

  return (
    <form onSubmit={submit}>
      <div className="grid2">
        <Field label="Customer name *"><input value={p.name} onChange={set('name')} required /></Field>
        <Field label="Business name"><input value={v(p.businessName)} onChange={set('businessName')} /></Field>
        <Field label="Contact person"><input value={v(p.contactPerson)} onChange={set('contactPerson')} /></Field>
        <Field label="Mobile *"><input value={p.phone} onChange={set('phone')} inputMode="numeric" maxLength={10} required /></Field>
        <Field label="Alternate mobile"><input value={v(p.alternatePhone)} onChange={set('alternatePhone')} inputMode="numeric" maxLength={10} /></Field>
        <Field label="Email"><input type="email" value={v(p.email)} onChange={set('email')} /></Field>
        <Field label="City"><input value={v(p.city)} onChange={set('city')} /></Field>
        <Field label="State"><input value={v(p.state)} onChange={set('state')} /></Field>
        <Field label="Pincode"><input value={v(p.pincode)} onChange={set('pincode')} inputMode="numeric" maxLength={6} /></Field>
        <Field label="Credit period (days)"><input type="number" min={0} max={365} value={p.creditPeriodDays} onChange={set('creditPeriodDays')} /></Field>
        <Field label="GSTIN"><input value={v(p.gstin)} onChange={set('gstin')} maxLength={15} /></Field>
        <Field label="PAN"><input value={v(p.pan)} onChange={set('pan')} maxLength={10} /></Field>
      </div>
      <Field label="Address"><textarea value={v(p.address)} onChange={set('address')} rows={2} /></Field>

      {mode === 'create' && (
        <fieldset className="grid3">
          <legend>Opening position</legend>
          <Field label="Credit limit (₹)" hint={canSetLimit ? undefined : 'Only admin can set a credit limit'}>
            <input type="number" min={0} step="0.01" value={extras.creditLimit} disabled={!canSetLimit}
                   onChange={(e) => setExtras({ ...extras, creditLimit: e.target.value })} />
          </Field>
          <Field label="Opening outstanding (₹)" hint="Negative = advance held">
            <input type="number" step="0.01" value={extras.openingBalance}
                   onChange={(e) => setExtras({ ...extras, openingBalance: e.target.value })} />
          </Field>
          <Field label="As on date">
            <input type="date" value={extras.openingBalanceDate}
                   onChange={(e) => setExtras({ ...extras, openingBalanceDate: e.target.value })} />
          </Field>
        </fieldset>
      )}
      <ErrorBox error={error} />
      <button className="primary" disabled={busy}>{busy ? 'Saving…' : mode === 'create' ? 'Create customer' : 'Save changes'}</button>
    </form>
  );
}
