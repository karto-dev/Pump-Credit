import { useEffect, useRef, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth';
import NotificationBell from './NotificationBell';
import { ADMIN_PERMS } from '../pages/admin/Admin';

const CUSTOMER_VIEW_PERMS = ['CUSTOMER_MANAGE', 'INDENT_VIEW_ALL', 'CREDIT_TXN_VIEW_ALL', 'PAYMENT_VIEW_ALL',
  'LEDGER_VIEW_ALL', 'CREDIT_LIMIT_CHANGE'];

type NavItem = { to: string; label: string; icon: string; show: boolean };

const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase();

export default function Layout() {
  const { user, logout, can } = useAuth();
  const location = useLocation();
  const [sideOpen, setSideOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const menu = useRef<HTMLDivElement>(null);

  useEffect(() => { setSideOpen(false); setMenuOpen(false); }, [location.pathname]);
  useEffect(() => {
    const close = (e: MouseEvent) => { if (menu.current && !menu.current.contains(e.target as Node)) setMenuOpen(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  if (!user) return null;
  const isCustomer = user.role === 'CUSTOMER';

  const groups: { title: string; items: NavItem[] }[] = [
    { title: '', items: [
      { to: '/', label: 'Dashboard', icon: 'bi-grid-1x2', show: true },
    ] },
    { title: 'Fuel', items: [
      { to: '/indents/new', label: 'New indent', icon: 'bi-plus-circle', show: isCustomer && can('INDENT_CREATE') },
      { to: '/indents', label: isCustomer ? 'My indents' : 'Indents', icon: 'bi-clipboard-check', show: isCustomer || can('INDENT_VIEW_ALL') || can('INDENT_PROCESS') },
      { to: '/sales/new', label: 'New sale', icon: 'bi-fuel-pump', show: can('CREDIT_TXN_CREATE') },
      { to: '/sales', label: isCustomer ? 'My purchases' : 'Sales', icon: 'bi-receipt', show: isCustomer || can('CREDIT_TXN_VIEW_ALL') },
      { to: '/disputes', label: isCustomer ? 'My disputes' : 'Disputes', icon: 'bi-exclamation-octagon', show: isCustomer || can('DISPUTE_RESOLVE') || can('CREDIT_TXN_VIEW_ALL') },
    ] },
    { title: 'Money', items: [
      { to: '/payments/new', label: 'Pay', icon: 'bi-qr-code-scan', show: isCustomer && can('PAYMENT_SUBMIT') },
      { to: '/payments', label: isCustomer ? 'My payments' : 'Payments', icon: 'bi-wallet2', show: isCustomer || ['PAYMENT_VIEW_ALL', 'PAYMENT_VERIFY', 'PAYMENT_RECORD'].some(can) },
      { to: '/statement', label: 'Statement', icon: 'bi-journal-text', show: isCustomer && can('LEDGER_VIEW_OWN') },
      { to: '/adjustments', label: 'Adjustments', icon: 'bi-sliders', show: !isCustomer && ['ADJUSTMENT_APPROVE', 'ADJUSTMENT_CREATE', 'LEDGER_VIEW_ALL'].some(can) },
      { to: '/reports', label: 'Reports', icon: 'bi-bar-chart-line', show: !isCustomer && (can('REPORT_VIEW') || can('REPORT_FINANCIAL')) },
    ] },
    { title: 'Setup', items: [
      { to: '/customers', label: 'Customers', icon: 'bi-people', show: !isCustomer && CUSTOMER_VIEW_PERMS.some(can) },
      { to: '/fuel-rates', label: 'Fuel rates', icon: 'bi-currency-rupee', show: can('FUEL_RATE_MANAGE') },
      { to: '/users', label: 'Users', icon: 'bi-person-badge', show: can('USER_MANAGE') },
      { to: '/permissions', label: 'Permissions', icon: 'bi-shield-lock', show: can('PERMISSION_MANAGE') },
      { to: '/admin', label: 'Admin', icon: 'bi-gear', show: !isCustomer && ADMIN_PERMS.some(can) },
    ] },
  ].map((g) => ({ ...g, items: g.items.filter((i) => i.show) })).filter((g) => g.items.length > 0);

  // Longest matching link names the page in the top bar.
  const current = groups.flatMap((g) => g.items)
    .filter((i) => (i.to === '/' ? location.pathname === '/' : location.pathname.startsWith(i.to)))
    .sort((a, b) => b.to.length - a.to.length)[0];
  const role = user.role.charAt(0) + user.role.slice(1).toLowerCase();

  return (
    <div className="app">
      <aside className={`sidebar${sideOpen ? ' open' : ''}`}>
        <div className="brand"><span className="brand-mark"><i className="bi bi-fuel-pump-fill" /></span>Pump Credit</div>
        <nav className="side-nav">
          {groups.map((g) => (
            <div key={g.title || 'home'}>
              {g.title && <div className="side-group">{g.title}</div>}
              {g.items.map((l) => (
                <NavLink key={l.to} to={l.to} end={l.to === '/' || l.to === '/indents' || l.to === '/sales' || l.to === '/payments'}>
                  <i className={`bi ${l.icon}`} />{l.label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="side-foot">Credit sales &amp; recovery</div>
      </aside>
      {sideOpen && <div className="side-scrim" onClick={() => setSideOpen(false)} />}

      <div className="main">
        <header className="topbar">
          <button className="icon menu-toggle" aria-label="Open menu" onClick={() => setSideOpen(true)}><i className="bi bi-list" /></button>
          <span className="brand"><span className="brand-mark"><i className="bi bi-fuel-pump-fill" /></span></span>
          <span className="topbar-title">{current?.label ?? ''}</span>
          <div className="who">
            <NotificationBell />
            <div className="user-menu" ref={menu}>
              <button className="user-btn" onClick={() => setMenuOpen(!menuOpen)} aria-label="Account menu">
                <span className="avatar">{initials(user.fullName)}</span>
                <span className="user-meta"><b>{user.fullName}</b><span>{role}</span></span>
                <i className="bi bi-chevron-down small muted" />
              </button>
              {menuOpen && (
                <div className="menu-panel">
                  <div className="menu-head"><b>{user.fullName}</b><div className="small muted">{user.username} · {role}</div></div>
                  <NavLink to="/change-password"><i className="bi bi-key" />Change password</NavLink>
                  <button className="danger-item" onClick={logout}><i className="bi bi-box-arrow-right" />Sign out</button>
                </div>
              )}
            </div>
          </div>
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
