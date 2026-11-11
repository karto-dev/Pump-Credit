import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { api, tokenStore, type Me } from './api/client';

interface AuthState {
  user: Me | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<Me>;
  logout: () => Promise<void>;
  can: (permission: string) => boolean;
  setUser: (user: Me) => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!tokenStore.get()) {
      setLoading(false);
      return;
    }
    api<Me>('/auth/me')
      .then(setUser)
      .catch(() => tokenStore.clear())
      .finally(() => setLoading(false));
  }, []);

  const login = async (username: string, password: string) => {
    const res = await api<{ token: string; user: Me }>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
    tokenStore.set(res.token);
    setUser(res.user);
    return res.user;
  };

  // Sign out locally first, then tell the server without waiting: a slow or unreachable backend
  // must never leave the user stuck half signed out.
  const logout = async () => {
    const token = tokenStore.get();
    tokenStore.clear();
    setUser(null);
    if (token) {
      fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: `Bearer ${token}` }, keepalive: true })
        .catch(() => undefined);
    }
  };

  useIdleLogout(user?.idleMinutes ?? 0, async () => {
    await logout();
    window.location.replace('/login?reason=idle');
  });

  const can = (p: string) => !!user?.permissions.includes(p);

  return <AuthContext.Provider value={{ user, loading, login, logout, can, setUser }}>{children}</AuthContext.Provider>;
}

const ACTIVITY_EVENTS = ['mousedown', 'mousemove', 'keydown', 'touchstart', 'scroll', 'wheel'] as const;

/** Calls onIdle once the user has done nothing for `minutes` (0 = off). Background polling does not count. */
function useIdleLogout(minutes: number, onIdle: () => void) {
  const onIdleRef = useRef(onIdle);
  onIdleRef.current = onIdle;

  useEffect(() => {
    if (minutes <= 0) return;
    const limitMs = minutes * 60_000;
    let lastActive = Date.now();
    const touch = () => { lastActive = Date.now(); };
    // Timers are paused while a laptop sleeps, so also check the moment the tab is visible again.
    const check = () => { if (Date.now() - lastActive >= limitMs) onIdleRef.current(); };
    const onVisible = () => { if (document.visibilityState === 'visible') check(); };

    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, touch, { passive: true }));
    document.addEventListener('visibilitychange', onVisible);
    const timer = setInterval(check, 5_000);
    return () => {
      ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, touch));
      document.removeEventListener('visibilitychange', onVisible);
      clearInterval(timer);
    };
  }, [minutes]);
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
