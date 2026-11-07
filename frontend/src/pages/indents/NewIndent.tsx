import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api, formatINR } from '../../api/client';
import type { CustomerDetail, CustomerRow, FuelRate, Indent, Vehicle } from '../../api/types';
import { useAuth } from '../../auth';
import { ErrorBox, Field, nullIfBlank, useAction } from '../../components/ui';
import { IndentBadge } from '../../components/status';

type Mode = 'QTY' | 'AMOUNT' | 'FULL';

export default function NewIndent() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const isCustomer = user?.role === 'CUSTOMER';
  const [customerId, setCustomerId] = useState<number | null>(isCustomer ? user!.customerId : null);
  const [customers, setCustomers] = useState<CustomerRow[]>([]);
  const [account, setAccount] = useState<CustomerDetail | null>(null);
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [rates, setRates] = useState<FuelRate[]>([]);
  const [vehicleId, setVehicleId] = useState<number | null>(null);
  const [fuel, setFuel] = useState<'DIESEL' | 'PETROL'>('DIESEL');
  const [mode, setMode] = useState<Mode>('QTY');
  const [value, setValue] = useState('');
  const [instructions, setInstructions] = useState('');
  const [created, setCreated] = useState<Indent | null>(null);
  const { busy, error, run } = useAction();

  useEffect(() => {
    api<FuelRate[]>('/fuel-rates/current').then(setRates).catch(() => undefined);
    if (!isCustomer) api<CustomerRow[]>('/customers?status=ACTIVE').then(setCustomers).catch(() => undefined);
  }, [isCustomer]);

  useEffect(() => {
    if (!customerId) return;
    api<CustomerDetail>(`/customers/${customerId}`).then(setAccount).catch(() => undefined);
    api<Vehicle[]>(`/customers/${customerId}/vehicles`).then((v) => {
      const active = v.filter((x) => x.isActive);
      setVehicles(active);
      if (active.length === 1) pickVehicle(active[0]);
    });
  }, [customerId]);

  const pickVehicle = (v: Vehicle) => {
    setVehicleId(v.id);
    if (v.defaultFuel) setFuel(v.defaultFuel);
  };

  const rate = rates.find((r) => r.fuelType === fuel)?.ratePerLitre;
  const estimate = mode === 'AMOUNT' ? Number(value) : mode === 'QTY' && rate ? Number(value) * rate : null;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const r = await run(() => api<Indent>('/indents', {
      method: 'POST',
      body: JSON.stringify({
        customerId, vehicleId, fuelType: fuel, fullTank: mode === 'FULL',
        requestedQuantity: mode === 'QTY' ? Number(value) : null,
        requestedAmount: mode === 'AMOUNT' ? Number(value) : null,
        instructions: nullIfBlank(instructions),
      }),
    }));
    if (r) setCreated(r);
  };

  if (created) {
    return (
      <div className="narrow">
        <div className="card center-card">
          <div className={`big-icon ${created.status === 'PENDING' ? 'ok-text' : 'warn-text'}`}><i className={`bi ${created.status === 'PENDING' ? 'bi-check-circle-fill' : 'bi-hourglass-split'}`} /></div>
          <h2 className="center-h">Indent {created.indentNo}</h2>
          <IndentBadge status={created.status} />
          {created.status === 'ON_HOLD_CREDIT_LIMIT'
            ? <p className="notice">{created.holdReason}. The admin has been notified to review it.</p>
            : <p className="muted">The cashier has been notified. Show the vehicle number <b className="mono">{created.vehicleNumber}</b> at the pump.</p>}
          <div className="actions stack">
            <button className="primary" onClick={() => navigate('/indents')}>View my indents</button>
            <button onClick={() => { setCreated(null); setValue(''); setInstructions(''); }}>Raise another</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="narrow">
      <form className="card" onSubmit={submit}>
        <h2>New fuel indent</h2>
        {!isCustomer && (
          <Field label="Customer *">
            <select value={customerId ?? ''} onChange={(e) => { setCustomerId(Number(e.target.value) || null); setVehicleId(null); }} required>
              <option value="">Select customer…</option>
              {customers.map((c) => <option key={c.id} value={c.id}>{c.name} ({c.customerCode})</option>)}
            </select>
          </Field>
        )}
        {account && (
          <p className={Number(account.availableCredit) <= 0 ? 'notice' : 'muted'}>
            Available credit: <b>{formatINR(account.availableCredit)}</b>
            {Number(account.availableCredit) <= 0 && ' — this indent will wait for admin approval.'}
          </p>
        )}

        <div className="field-label">Vehicle *</div>
        <div className="chips">
          {vehicles.map((v) => (
            <button type="button" key={v.id} className={`chip mono${vehicleId === v.id ? ' selected' : ''}`} onClick={() => pickVehicle(v)}>
              {v.vehicleNumber}
            </button>
          ))}
          {customerId && vehicles.length === 0 && <span className="muted">No registered vehicles. Ask the pump to add one.</span>}
        </div>

        <div className="field-label">Fuel *</div>
        <div className="segmented">
          {(['DIESEL', 'PETROL'] as const).map((f) => (
            <button type="button" key={f} className={fuel === f ? 'selected' : ''} onClick={() => setFuel(f)}>
              {f === 'DIESEL' ? 'Diesel' : 'Petrol'}
              {rates.find((r) => r.fuelType === f) && <span className="small"> · {formatINR(rates.find((r) => r.fuelType === f)!.ratePerLitre)}/L</span>}
            </button>
          ))}
        </div>

        <div className="field-label">How much? *</div>
        <div className="segmented">
          <button type="button" className={mode === 'QTY' ? 'selected' : ''} onClick={() => setMode('QTY')}>Litres</button>
          <button type="button" className={mode === 'AMOUNT' ? 'selected' : ''} onClick={() => setMode('AMOUNT')}>Amount ₹</button>
          <button type="button" className={mode === 'FULL' ? 'selected' : ''} onClick={() => setMode('FULL')}>Full tank</button>
        </div>
        {mode !== 'FULL' && (
          <Field label={mode === 'QTY' ? 'Estimated litres' : 'Estimated amount (₹)'}
                 hint={estimate ? `≈ ${formatINR(estimate)}` : undefined}>
            <input type="number" inputMode="decimal" min={mode === 'QTY' ? 0.001 : 1} step={mode === 'QTY' ? 0.001 : 1}
                   value={value} onChange={(e) => setValue(e.target.value)} required />
          </Field>
        )}
        <Field label="Instructions">
          <textarea rows={2} maxLength={500} value={instructions} onChange={(e) => setInstructions(e.target.value)}
                    placeholder="e.g. Driver Ramesh will come after 4 PM" />
        </Field>
        <ErrorBox error={error} />
        <button className="primary" disabled={busy || !vehicleId}>{busy ? 'Sending…' : 'Raise indent'}</button>
        <p className="center small"><Link to="/indents">View my indents</Link></p>
      </form>
    </div>
  );
}
