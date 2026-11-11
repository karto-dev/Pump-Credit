export type Role = 'CUSTOMER' | 'CASHIER' | 'MANAGER' | 'ADMIN';

export interface Me {
  id: number;
  username: string;
  fullName: string;
  role: Role;
  customerId: number | null;
  mustChangePassword: boolean;
  permissions: string[];
  idleMinutes: number;
}

const TOKEN_KEY = 'ppc.token';

export const tokenStore = {
  get: () => sessionStorage.getItem(TOKEN_KEY),
  set: (t: string) => sessionStorage.setItem(TOKEN_KEY, t),
  clear: () => sessionStorage.removeItem(TOKEN_KEY),
};

export class ApiError extends Error {
  constructor(public status: number, message: string, public body: Record<string, unknown> = {}) {
    super(message);
  }
}

// Long enough for a sleeping free-tier backend to wake up (about a minute), short enough that a stuck
// request ends in an error message instead of a spinner that never stops.
const REQUEST_TIMEOUT_MS = 75_000;

/** fetch() that gives up after REQUEST_TIMEOUT_MS. Timeouts and network failures become ApiError(0). */
async function fetchWithTimeout(url: string, init: RequestInit = {}): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } catch {
    throw new ApiError(0, controller.signal.aborted
      ? 'The server is taking too long to respond. Please try again.'
      : 'Cannot reach the server. Check your connection and try again.');
  } finally {
    clearTimeout(timer);
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const isLogin = path === '/auth/login';
  const headers = new Headers(init.headers);
  const token = tokenStore.get();
  // Never send an old token with the login itself.
  if (token && !isLogin) headers.set('Authorization', `Bearer ${token}`);
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json');

  const res = await fetchWithTimeout(`/api${path}`, { ...init, headers });
  if (res.status === 401 && !isLogin) {
    tokenStore.clear();
    window.location.assign('/login?reason=expired');
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, body.detail ?? body.message ?? `Request failed (${res.status})`, body);
  }
  return res.status === 204 || res.headers.get('content-length') === '0' ? (undefined as T) : res.json();
}

/** Authenticated binary fetch (bill photos, proofs) -> object URL for <img>/<iframe>. Caller revokes it. */
export async function apiBlobUrl(path: string): Promise<{ url: string; type: string }> {
  const res = await fetchWithTimeout(`/api${path}`, { headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}` } });
  if (!res.ok) throw new ApiError(res.status, `Could not load file (${res.status})`);
  const blob = await res.blob();
  return { url: URL.createObjectURL(blob), type: blob.type };
}

/** YYYY-MM-DD in local time, for date inputs and API filters. */
export const isoDay = (d = new Date()) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

export const formatQty = (n: number) =>
  `${new Intl.NumberFormat('en-IN', { maximumFractionDigits: 3 }).format(Number(n))} L`;

// Indian formats: ₹1,23,456.00 and DD-MM-YYYY
export const formatINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(n);

export const formatDate = (iso: string) => {
  const d = new Date(iso);
  return `${String(d.getDate()).padStart(2, '0')}-${String(d.getMonth() + 1).padStart(2, '0')}-${d.getFullYear()}`;
};

export const formatDateTime = (iso: string) => {
  const d = new Date(iso);
  return `${formatDate(iso)} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

/** multipart/form-data POST: JSON parts plus optional files. Errors behave like api(). */
export async function apiForm<T>(path: string, json: Record<string, unknown>, files: Record<string, File | null> = {}): Promise<T> {
  const body = new FormData();
  for (const [name, value] of Object.entries(json)) {
    body.append(name, new Blob([JSON.stringify(value)], { type: 'application/json' }));
  }
  for (const [name, file] of Object.entries(files)) if (file) body.append(name, file);
  return api<T>(path, { method: 'POST', body });
}

/** Authenticated download (Excel / PDF exports) saved under the server's file name. */
export async function apiDownload(path: string, fallbackName: string): Promise<void> {
  const res = await fetchWithTimeout(`/api${path}`, { headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}` } });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, body.detail ?? `Download failed (${res.status})`, body);
  }
  const name = /filename="?([^";]+)"?/.exec(res.headers.get('content-disposition') ?? '')?.[1] ?? fallbackName;
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
