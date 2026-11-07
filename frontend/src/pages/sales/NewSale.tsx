import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, ApiError, formatINR, formatQty, tokenStore } from '../../api/client';
import type { CustomerDetail, CustomerRow, FuelRate, Indent, Sale, Vehicle } from '../../api/types';
import { ErrorBox, Field, Modal, nullIfBlank } from '../../components/ui';

/** Cashier screen: one customer, one fill, one bill photo. Built for a phone held in one hand at the pump. */
export default function NewSale() {
  const [params] = useSearchParams();
  const [rates, setRates] = useState<FuelRate[]>([]);
  const [q, setQ] = useState('');
  const [matches, setMatches] = useState<CustomerRow[]>([]);
  const [customer, setCustomer] = useState<CustomerDetail | null>(null);
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [openIndents, setOpenIndents] = useState<Indent[]>([]);
  const [indentId, setIndentId] = useState<number | null>(null);
  const [vehicleId, setVehicleId] = useState<number | null>(null);
  const [fuel, setFuel] = useState<'DIESEL' | 'PETROL'>('DIESEL');
  const [qty, setQty] = useState('');
  const [rate, setRate] = useState('');
  const [billNo, setBillNo] = useState('');
  const [remarks, setRemarks] = useState('');
  const [photo, setPhoto] = useState<File | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [duplicate, setDuplicate] = useState<string | null>(null);
  const [saved, setSaved] = useState<Sale | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => { api<FuelRate[]>('/fuel-rates/current').then(setRates).catch(() => undefined); }, []);

  // Rate follows fuel type until the cashier types their own.
  const currentRate = rates.find((r) => r.fuelType === fuel)?.ratePerLitre;
  const rateTouched = useRef(false);
  useEffect(() => {
    if (!rateTouched.current && currentRate) setRate(String(currentRate));
  }, [currentRate]);

  // Deep link from the indent queue: /sales/new?indent=12
  useEffect(() => {
    const id = Number(params.get('indent'));
    if (!id) return;
    api<Indent>(`/indents/${id}`).then(async (ind) => {
      await selectCustomer(ind.customerId);
      applyIndent(ind);
    }).catch((e) => setError(e.message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  useEffect(() => {
    if (customer || q.trim().length < 2) { setMatches([]); return; }
    const t = setTimeout(() => api<CustomerRow[]>(`/customers?status=ACTIVE&q=${encodeURIComponent(q)}`).then(setMatches), 250);
    return () => clearTimeout(t);
  }, [q, customer]);

  const selectCustomer = async (id: number) => {
    const [c, v, ind] = await Promise.all([
      api<CustomerDetail>(`/customers/${id}`),
      api<Vehicle[]>(`/customers/${id}/vehicles`),
      api<Indent[]>(`/indents?customerId=${id}&status=PENDING,ACCEPTED`),
    ]);
    setCustomer(c);
    const active = v.filter((x) => x.isActive);
    setVehicles(active);
    setOpenIndents(ind);
    setMatches([]);
    if (ind.length === 1) applyIndent(ind[0]);
    else if (active.length === 1) { setVehicleId(active[0].id); if (active[0].defaultFuel) setFuel(active[0].defaultFuel); }
  };

  const applyIndent = (ind: Indent | null) => {
    setIndentId(ind?.id ?? null);
    if (ind) {
      setVehicleId(ind.vehicleId);
      setFuel(ind.fuelType);
      if (ind.requestedQuantity && !qty) setQty(String(ind.requestedQuantity));
    }
  };

  const pickPhoto = (f: File | null) => {
    if (preview) URL.revokeObjectURL(preview);
    setPhoto(f);
    setPreview(f && f.type.startsWith('image/') ? URL.createObjectURL(f) : null);
  };

  // Display only - the server computes the amount that is actually posted.
  const amount = useMemo(() => {
    const a = Number(qty) * Number(rate);
    return Number.isFinite(a) && a > 0 ? Math.round(a * 100) / 100 : 0;
  }, [qty, rate]);
  const overLimit = !indentId && customer && amount > Number(customer.availableCredit);

  const reset = () => {
    setCustomer(null); setQ(''); setVehicles([]); setOpenIndents([]); setIndentId(null); setVehicleId(null);
    setQty(''); setBillNo(''); setRemarks(''); pickPhoto(null); setSaved(null); setError(null);
    rateTouched.current = false;
    if (currentRate) setRate(String(currentRate));
    if (fileInput.current) fileInput.current.value = '';
  };

  const submit = async (confirmDuplicate = false) => {
    if (!customer || !vehicleId || !photo) return;
    setBusy(true); setError(null); setDuplicate(null);
    const body = new FormData();
    body.append('sale', new Blob([JSON.stringify({
      customerId: customer.id, indentId, vehicleId, fuelType: fuel, quantity: Number(qty), ratePerLitre: Number(rate),
      physicalBillNo: nullIfBlank(billNo), remarks: nullIfBlank(remarks), confirmDuplicate,
    })], { type: 'application/json' }));
    body.append('bill', photo);
    try {
      const res = await fetch('/api/credit-sales', {
        method: 'POST', body, headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}` },
      });
      const json = await res.json().catch(() => ({}));
      if (!res.ok) throw new ApiError(res.status, json.detail ?? `Failed (${res.status})`, json);
      setSaved(json as Sale);
    } catch (e) {
      if (e instanceof ApiError && e.body.duplicate) setDuplicate(e.message);
      else setError(e instanceof Error ? e.message : 'Could not save');
    } finally {
      setBusy(false);
    }
  };

  if (saved) {
    return (
      <div className="narrow">
        <div className="card center-card">
          <div className="big-icon ok-text"><i className="bi bi-check-circle-fill" /></div>
          <h2 className="center-h">{saved.txnNo} saved</h2>
          <p><b>{formatQty(saved.quantity)}</b> {saved.fuelType} · <b>{formatINR(saved.amount)}</b></p>
          <p className="muted">{saved.customerName} · <span className="mono">{saved.vehicleNumber}</span></p>
          <p className="muted small">Posted to the customer's ledger. The customer has been asked to review the bill.</p>
          <div className="actions stack">
            <button className="primary" onClick={reset}>Next sale</button>
            <Link to="/indents" className="button">Back to indents</Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="narrow">
      <form className="card" onSubmit={(e: FormEvent) => { e.preventDefault(); submit(); }}>
        <h2>Credit sale</h2>

        {!customer ? (
          <Field label="Customer *">
            <input placeholder="Type name, phone or vehicle no." value={q} onChange={(e) => setQ(e.target.value)} autoFocus />
            {matches.length > 0 && (
              <div className="suggestions">
                {matches.map((c) => (
                  <button type="button" key={c.id} onClick={() => selectCustomer(c.id)}>
                    <b>{c.name}</b> <span className="muted small">{c.customerCode} · {c.phone}</span>
                    <span className={`small ${Number(c.availableCredit) < 0 ? 'bad-text' : 'muted'}`}> · avail {formatINR(c.availableCredit)}</span>
                  </button>
                ))}
              </div>
            )}
          </Field>
        ) : (
          <div className="selected-customer">
            <div>
              <b>{customer.name}</b> <span className="muted small">{customer.customerCode}</span>
              <div className={`small ${Number(customer.availableCredit) < 0 ? 'bad-text' : 'muted'}`}>
                Available credit {formatINR(customer.availableCredit)}
              </div>
            </div>
            <button type="button" className="link" onClick={reset}>Change</button>
          </div>
        )}

        {customer && <>
          <Field label="Indent">
            <select value={indentId ?? ''} onChange={(e) => applyIndent(openIndents.find((i) => i.id === Number(e.target.value)) ?? null)}>
              <option value="">Without indent</option>
              {openIndents.map((i) => (
                <option key={i.id} value={i.id}>
                  {i.indentNo} · {i.vehicleNumber} · {i.fuelType} · {i.fullTank ? 'full tank' : i.requestedQuantity ? formatQty(i.requestedQuantity) : formatINR(i.requestedAmount!)}
                </option>
              ))}
            </select>
          </Field>

          <div className="field-label">Vehicle *</div>
          <div className="chips">
            {vehicles.map((v) => (
              <button type="button" key={v.id} disabled={!!indentId && v.id !== vehicleId}
                      className={`chip mono${vehicleId === v.id ? ' selected' : ''}`}
                      onClick={() => { setVehicleId(v.id); if (v.defaultFuel) setFuel(v.defaultFuel); }}>
                {v.vehicleNumber}
              </button>
            ))}
          </div>

          <div className="field-label">Fuel *</div>
          <div className="segmented">
            {(['DIESEL', 'PETROL'] as const).map((f) => (
              <button type="button" key={f} disabled={!!indentId && fuel !== f} className={fuel === f ? 'selected' : ''}
                      onClick={() => { setFuel(f); rateTouched.current = false; }}>
                {f === 'DIESEL' ? 'Diesel' : 'Petrol'}
              </button>
            ))}
          </div>

          <div className="grid2">
            <Field label="Actual quantity (L) *">
              <input type="number" inputMode="decimal" min={0.001} step={0.001} value={qty} onChange={(e) => setQty(e.target.value)} required />
            </Field>
            <Field label="Rate per litre (₹) *" hint={currentRate && Number(rate) !== Number(currentRate) ? `Pump rate is ${formatINR(currentRate)}` : undefined}>
              <input type="number" inputMode="decimal" min={1} step={0.01} value={rate}
                     onChange={(e) => { rateTouched.current = true; setRate(e.target.value); }} required />
            </Field>
          </div>
          <div className={`amount-box${overLimit ? ' over' : ''}`}>
            <span>Amount</span><b>{formatINR(amount)}</b>
          </div>
          {overLimit && <p className="error">Exceeds available credit. Ask the customer to raise an indent for admin approval.</p>}

          <div className="field-label">Bill photo *</div>
          <label className="photo-drop">
            <input ref={fileInput} type="file" accept="image/*,application/pdf" capture="environment"
                   onChange={(e) => pickPhoto(e.target.files?.[0] ?? null)} required />
            {preview ? <img src={preview} alt="Bill preview" /> : photo ? <span><i className="bi bi-file-earmark-check" /> {photo.name}</span> : <span><i className="bi bi-camera fs-3 d-block mb-1" />Tap to take a photo of the bill</span>}
          </label>

          <div className="grid2">
            <Field label="Physical bill no."><input value={billNo} onChange={(e) => setBillNo(e.target.value)} maxLength={40} /></Field>
            <Field label="Remarks"><input value={remarks} onChange={(e) => setRemarks(e.target.value)} maxLength={500} /></Field>
          </div>
          <ErrorBox error={error} />
          <button className="primary" disabled={busy || !vehicleId || !photo || !qty || !!overLimit}>
            {busy ? 'Saving…' : `Save sale · ${formatINR(amount)}`}
          </button>
        </>}
      </form>

      {duplicate && (
        <Modal title="Possible duplicate" onClose={() => setDuplicate(null)}>
          <p className="notice">{duplicate}</p>
          <p className="muted">Save only if this really is a separate fill. The override is recorded in the audit log.</p>
          <div className="actions">
            <button onClick={() => setDuplicate(null)}>Go back</button>
            <button className="primary auto danger" onClick={() => submit(true)} disabled={busy}>Save anyway</button>
          </div>
        </Modal>
      )}
    </div>
  );
}
