import { useEffect, useState, type FormEvent } from 'react';
import { api, formatDateTime, formatINR } from '../../api/client';
import type { FuelRate } from '../../api/types';
import { ErrorBox, Field, useAction } from '../../components/ui';

export default function FuelRates() {
  const [current, setCurrent] = useState<FuelRate[]>([]);
  const [history, setHistory] = useState<FuelRate[]>([]);
  const [fuel, setFuel] = useState<'DIESEL' | 'PETROL'>('DIESEL');
  const [rate, setRate] = useState('');
  const load = useAction();
  const save = useAction();

  const refresh = () => load.run(async () => {
    const [c, h] = await Promise.all([api<FuelRate[]>('/fuel-rates/current'), api<FuelRate[]>('/fuel-rates/history')]);
    setCurrent(c);
    setHistory(h);
  });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { refresh(); }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const r = await save.run(() => api('/fuel-rates', { method: 'POST', body: JSON.stringify({ fuelType: fuel, ratePerLitre: Number(rate) }) }));
    if (r) { setRate(''); refresh(); }
  };

  return (
    <>
      <h2>Fuel rates</h2>
      <p className="muted">Prefills the cashier's rate and values quantity-based indents for the credit-limit check. Cashiers can still type the actual billed rate.</p>
      <div className="tiles">
        {current.map((r) => (
          <div className="card tile" key={r.fuelType}>
            <div className="muted">{r.fuelType}</div>
            <div className="value">{formatINR(r.ratePerLitre)}<span className="small muted"> / L</span></div>
            <div className="small muted">since {formatDateTime(r.effectiveFrom)}</div>
          </div>
        ))}
        {current.length === 0 && <div className="card tile muted">No rates set yet</div>}
      </div>

      <form className="card section narrow-form" onSubmit={submit}>
        <h3>Set new rate</h3>
        <div className="grid2">
          <Field label="Fuel">
            <select value={fuel} onChange={(e) => setFuel(e.target.value as 'DIESEL' | 'PETROL')}>
              <option value="DIESEL">Diesel</option><option value="PETROL">Petrol</option>
            </select>
          </Field>
          <Field label="Rate per litre (₹)">
            <input type="number" min={1} max={500} step={0.01} value={rate} onChange={(e) => setRate(e.target.value)} required />
          </Field>
        </div>
        <ErrorBox error={save.error} />
        <button className="primary auto" disabled={save.busy}>Apply from now</button>
      </form>

      <section className="card section">
        <h3>History</h3>
        <ErrorBox error={load.error} />
        <div className="table-wrap">
          <table>
            <thead><tr><th>From</th><th>Fuel</th><th className="num">Rate</th><th>Set by</th></tr></thead>
            <tbody>
              {history.map((r, i) => (
                <tr key={i}><td>{formatDateTime(r.effectiveFrom)}</td><td>{r.fuelType}</td><td className="num">{formatINR(r.ratePerLitre)}</td><td>{r.setByName ?? '—'}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}
