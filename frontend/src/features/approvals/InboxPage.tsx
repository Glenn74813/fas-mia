import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { inboxApi } from '../../api/endpoints';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { formatDateTime } from '../../utils/format';

/** Everything waiting for the signed-in user across modules, oldest first. The step itself is taken on the document. */
export function InboxPage() {
  const [params, setParams] = useSearchParams();
  const queue = params.get('queue') ?? '';
  const search = params.get('q') ?? '';
  const [text, setText] = useState(search);
  const inbox = useQuery({ queryKey: ['inbox', search], queryFn: () => inboxApi.mine(search), refetchInterval: 60_000,
    placeholderData: keepPreviousData });
  const items = (inbox.data?.items ?? []).filter((i) => !queue || i.queue === queue);
  const update = (next: { queue?: string; q?: string }) => {
    const merged = { queue, q: search, ...next };
    setParams(Object.fromEntries(Object.entries(merged).filter(([, v]) => v)), { replace: true });
  };
  const pick = (q: string) => update({ queue: q });
  const chip = (active: boolean) => `rounded-full px-3 py-1 text-sm ${active ? 'bg-brand-700 text-white' : 'border border-slate-300 bg-white hover:bg-slate-50'}`;
  return (
    <>
      <PageHeader title="Inbox" />
      <p className="mb-4 text-sm text-slate-600">Documents waiting for a step you are allowed to take. Documents you prepared, and workflow
        steps after one you already approved, are not shown (segregation of duties). Open a document to review it and act.</p>
      <form role="search" className="mb-3 flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); update({ q: text.trim(), queue: '' }); }}>
        <label className="text-sm">Search <input type="search" value={text} onChange={(e) => setText(e.target.value)}
          placeholder="Number, description, counterparty or preparer" className="ml-1 w-80 max-w-full rounded-md border border-slate-300 px-2 py-1" /></label>
        <button type="submit" className="h-9 rounded-lg border border-slate-300 bg-white px-3.5 text-sm font-medium hover:bg-slate-50">Search</button>
        {search && <button type="button" className="text-sm text-brand-700 hover:underline" onClick={() => { setText(''); update({ q: '' }); }}>Clear search</button>}
      </form>
      <ErrorAlert error={inbox.error} />
      {inbox.isPending ? <Spinner /> : inbox.data && (
        <>
          {inbox.data.queues.length > 0 && (
            <div className="mb-3 flex flex-wrap gap-2" role="group" aria-label="Filter by queue">
              <button className={chip(!queue)} aria-pressed={!queue} onClick={() => pick('')}>All ({inbox.data.items.length})</button>
              {inbox.data.queues.map((q) => (
                <button key={q.queue} className={chip(queue === q.queue)} aria-pressed={queue === q.queue} onClick={() => pick(q.queue)}>
                  {q.queue} ({q.count})</button>))}
            </div>
          )}
          {inbox.data.truncated && <p role="status" className="mb-3 rounded-md bg-amber-50 p-2 text-sm text-amber-900">Only the oldest items
            of a very long queue are listed. Search to find a particular document.</p>}
          <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
            <table className="min-w-full text-sm">
              <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Waiting since</th>
                <th className="px-3 py-2">To do</th><th className="px-3 py-2">Document</th><th className="px-3 py-2">Counterparty</th>
                <th className="px-3 py-2 text-right">Amount</th><th className="px-3 py-2">Prepared by</th><th /></tr></thead>
              <tbody>
                {items.map((i) => (
                  <tr key={`${i.documentType}-${i.id}-${i.task}-${i.date ?? ''}-${i.link}`} className="border-t border-slate-100">
                    <td className="whitespace-nowrap px-3 py-2">{formatDateTime(i.waitingSince)}</td>
                    <td className="px-3 py-2 font-medium">{i.task}</td>
                    <td className="px-3 py-2">{i.number && <span className="font-mono">{i.number} </span>}{i.description}
                      <span className="block text-xs text-slate-500">{i.queue}{i.date ? ` · ${i.date}` : ''}</span></td>
                    <td className="px-3 py-2">{i.counterparty ?? '—'}</td>
                    <td className="px-3 py-2 text-right">{i.amount ? <Money value={i.amount} /> : '—'}</td>
                    <td className="px-3 py-2">{i.preparedBy}</td>
                    <td className="px-3 py-2 text-right"><Link className="text-brand-700 hover:underline" to={i.link}
                      aria-label={`Open ${i.number ?? i.description ?? i.queue}`}>Open</Link></td>
                  </tr>
                ))}
                {items.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">Nothing is waiting for you.</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  );
}
