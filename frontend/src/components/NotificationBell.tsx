import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, formatDateTime } from '../api/client';
import type { Notification } from '../api/types';

const ROUTE: Record<string, (id: number) => string> = {
  INDENT: () => '/indents',
  CREDIT_TRANSACTION: () => '/sales',
  CUSTOMER: (id) => `/customers/${id}`,
  DISPUTE: () => '/disputes',
  ADJUSTMENT: () => '/adjustments',
  PAYMENT: (id) => `/payments?status=&open=${id}`,
  BACKUP: () => '/admin?t=backups',
};

export default function NotificationBell() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState<Notification[]>([]);
  const box = useRef<HTMLDivElement>(null);

  const load = useCallback(() => {
    api<{ unread: number; items: Notification[] }>('/notifications')
      .then((r) => { setUnread(r.unread); setItems(r.items); })
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 30000);
    return () => clearInterval(t);
  }, [load]);

  useEffect(() => {
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  const openItem = async (n: Notification) => {
    if (!n.isRead) await api(`/notifications/${n.id}/read`, { method: 'POST' }).catch(() => undefined);
    setOpen(false);
    load();
    const route = n.entityType && ROUTE[n.entityType];
    if (route) navigate(route(n.entityId ?? 0));
  };

  return (
    <div className="bell" ref={box}>
      <button className="icon" aria-label={`Notifications (${unread} unread)`} onClick={() => setOpen(!open)}>
        <i className="bi bi-bell" />{unread > 0 && <span className="bell-count">{unread > 99 ? '99+' : unread}</span>}
      </button>
      {open && (
        <div className="bell-panel card">
          <div className="section-head">
            <b>Notifications</b>
            {unread > 0 && <button className="link small" onClick={() => api('/notifications/read-all', { method: 'POST' }).then(load)}>Mark all read</button>}
          </div>
          {items.length === 0 && <div className="muted small">Nothing yet</div>}
          {items.map((n) => (
            <button key={n.id} className={`bell-item${n.isRead ? '' : ' unread'}`} onClick={() => openItem(n)}>
              <b>{n.title}</b>
              <span className="small">{n.message}</span>
              <span className="small muted">{formatDateTime(n.createdAt)}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
