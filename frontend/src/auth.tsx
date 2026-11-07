import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
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

  const logout = async () => {
    await api('/auth/logout', { method: 'POST' }).catch(() => undefined);
    tokenStore.clear();
    setUser(null);
  };

  const can = (p: string) => !!user?.permissions.includes(p);

  return <AuthContext.Provider value={{ user, loading, login, logout, can, setUser }}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
