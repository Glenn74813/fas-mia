import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bell } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { notificationApi } from '../api/endpoints';
import type { AppNotification } from '../api/types';
import { formatDateTime } from '../utils/format';

/** The signed-in user's notifications: unread count on the button, the latest ten in a panel. */
export function NotificationBell() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const panel = useRef<HTMLDivElement>(null);
  const count = useQuery({ queryKey: ['notifications', 'count'], queryFn: notificationApi.unreadCount, refetchInterval: 60_000 });
  const list = useQuery({ queryKey: ['notifications', 'latest'], queryFn: () => notificationApi.list(false), enabled: open });
  const refresh = () => qc.invalidateQueries({ queryKey: ['notifications'] });
  const read = useMutation({ mutationFn: (n: AppNotification) => notificationApi.markRead(n.id), onSuccess: refresh });
  const readAll = useMutation({ mutationFn: notificationApi.markAllRead, onSuccess: refresh });
  const unread = count.data?.unread ?? 0;

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent | KeyboardEvent) => {
      if (e instanceof KeyboardEvent ? e.key === 'Escape' : !panel.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', close);
    return () => { document.removeEventListener('mousedown', close); document.removeEventListener('keydown', close); };
  }, [open]);

  const go = (n: AppNotification) => {
    if (!n.readAt) read.mutate(n);
    setOpen(false);
    if (n.link) navigate(n.link);
  };

  return (
    <div className="relative" ref={panel}>
      <button type="button" aria-expanded={open} aria-haspopup="true" onClick={() => setOpen((o) => !o)}
        aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
        className="relative rounded-lg p-2 text-slate-500 transition hover:bg-slate-100 hover:text-slate-800">
        <Bell aria-hidden className="h-5 w-5" strokeWidth={1.75} />
        {unread > 0 && <span aria-hidden className="absolute -right-1 -top-1 min-w-[18px] rounded-full bg-red-600 px-1 text-center text-[10px] font-semibold leading-[18px] text-white ring-2 ring-white">
          {unread > 99 ? '99+' : unread}</span>}
      </button>
      {open && (
        <div role="dialog" aria-label="Notifications" className="absolute right-0 z-20 mt-2 w-96 max-w-[calc(100vw-2rem)] overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xl ring-1 ring-black/5">
          <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
            <span className="text-sm font-semibold">Notifications</span>
            {unread > 0 && <button className="text-xs text-brand-700 hover:underline" disabled={readAll.isPending} onClick={() => readAll.mutate()}>
              Mark all as read</button>}
          </div>
          <ul className="max-h-96 overflow-y-auto">
            {(list.data?.items ?? []).map((n) => (
              <li key={n.id} className="border-b border-slate-100 last:border-0">
                <button className={`block w-full px-4 py-2 text-left text-sm hover:bg-slate-50 ${n.readAt ? 'text-slate-600' : 'bg-brand-50/60'}`} onClick={() => go(n)}>
                  <span className={n.readAt ? '' : 'font-medium text-slate-900'}>{!n.readAt && <span className="sr-only">Unread: </span>}{n.title}</span>
                  {n.body && <span className="mt-0.5 block truncate text-xs text-slate-500">{n.body}</span>}
                  <span className="mt-0.5 block text-xs text-slate-400">{formatDateTime(n.createdAt)}</span>
                </button>
              </li>
            ))}
            {list.isPending && <li className="px-4 py-3 text-sm text-slate-500">Loading…</li>}
            {list.data?.items.length === 0 && <li className="px-4 py-3 text-sm text-slate-500">No notifications yet.</li>}
          </ul>
        </div>
      )}
    </div>
  );
}
