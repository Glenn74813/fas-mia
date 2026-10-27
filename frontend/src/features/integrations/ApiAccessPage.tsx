import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { apiAccessApi } from '../../api/endpoints';
import type { ApiClient, WebhookSubscription } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ActionDialog, type ActionSpec } from '../../components/ActionDialog';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { formatDateTime, humanize } from '../../utils/format';

/** A secret shown once, with a copy button. */
function SecretOnce({ label, value, onDone }: { label: string; value: string; onDone: () => void }) {
  const [copied, setCopied] = useState(false);
  return (
    <div role="status" className="mb-4 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
      <p className="font-medium">{label}: copy it now. It is shown only once.</p>
      <p className="mt-2 break-all rounded bg-white p-2 font-mono text-xs text-slate-900">{value}</p>
      <div className="mt-2 flex gap-2">
        <Button variant="secondary" onClick={() => { void navigator.clipboard?.writeText(value); setCopied(true); }}>{copied ? 'Copied' : 'Copy'}</Button>
        <Button variant="secondary" onClick={onDone}>I have saved it</Button>
      </div>
    </div>
  );
}

function Checkboxes({ legend, options, value, onChange }: { legend: string; options: string[]; value: string[]; onChange: (v: string[]) => void }) {
  return (
    <fieldset className="text-sm"><legend className="mb-1 font-medium text-slate-800">{legend}</legend>
      <div className="grid gap-1 sm:grid-cols-2">
        {options.map((o) => (
          <label key={o} className="flex items-center gap-2"><input type="checkbox" checked={value.includes(o)}
            onChange={(e) => onChange(e.target.checked ? [...value, o] : value.filter((x) => x !== o))} />{humanize(o)}</label>
        ))}
      </div>
    </fieldset>
  );
}

export function ApiAccessPage() {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('API_CLIENT_MANAGE');
  const qc = useQueryClient();
  const catalog = useQuery({ queryKey: ['api-access', 'catalog'], queryFn: apiAccessApi.catalog });
  const clients = useQuery({ queryKey: ['api-access', 'clients'], queryFn: apiAccessApi.clients });
  const hooks = useQuery({ queryKey: ['api-access', 'webhooks'], queryFn: apiAccessApi.webhooks });
  const refresh = () => qc.invalidateQueries({ queryKey: ['api-access'] });
  const [secret, setSecret] = useState<{ label: string; value: string } | null>(null);

  const [clientName, setClientName] = useState('');
  const [scopes, setScopes] = useState<string[]>([]);
  const createClient = useMutation({
    mutationFn: () => apiAccessApi.createClient({ name: clientName, scopes }),
    onSuccess: (r) => { setSecret({ label: `Secret for ${r.client.clientId}`, value: r.clientSecret }); setClientName(''); setScopes([]); refresh(); },
  });
  const rotateClient = useMutation({
    mutationFn: apiAccessApi.rotateClient,
    onSuccess: (r) => { setSecret({ label: `New secret for ${r.client.clientId}`, value: r.clientSecret }); refresh(); },
  });
  const [revoking, setRevoking] = useState<ApiClient | null>(null);
  const revoke = useMutation({
    mutationFn: (reason: string) => apiAccessApi.revokeClient(revoking!, reason),
    onSuccess: () => { setRevoking(null); refresh(); },
  });

  const [hookName, setHookName] = useState('');
  const [url, setUrl] = useState('');
  const [events, setEvents] = useState<string[]>([]);
  const subscribe = useMutation({
    mutationFn: () => apiAccessApi.subscribe({ name: hookName, url, events }),
    onSuccess: (r) => { setSecret({ label: `Signing secret for ${r.subscription.name}`, value: r.signingSecret }); setHookName(''); setUrl(''); setEvents([]); refresh(); },
  });
  const setStatus = useMutation({ mutationFn: (a: { w: WebhookSubscription; s: 'ACTIVE' | 'PAUSED' }) => apiAccessApi.setStatus(a.w, a.s), onSuccess: refresh });
  const test = useMutation({ mutationFn: apiAccessApi.test, onSuccess: refresh });
  const [log, setLog] = useState<string | null>(null);
  const deliveries = useQuery({ queryKey: ['api-access', 'deliveries', log], queryFn: () => apiAccessApi.deliveries(log!), enabled: !!log });
  const redeliver = useMutation({ mutationFn: apiAccessApi.redeliver, onSuccess: refresh });

  const revokeSpec: ActionSpec | null = revoking && { title: `Revoke ${revoking.clientId}`, text: 'Its tokens stop working at once. This cannot be undone.',
    confirm: 'Revoke', commentRequired: true, commentLabel: 'Reason', danger: true };
  const input = 'block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-xs';
  const card = 'mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white p-4 shadow-xs';
  const th = 'px-3 py-2';

  return (
    <>
      <PageHeader title="API access" crumbs={[{ label: 'Administration' }, { label: 'API access' }]} />
      <p className="mb-4 max-w-3xl text-sm text-slate-600">Other systems read CAS data through the API as <strong>API clients</strong>: they
        get a 10-minute token from <code>POST /api/v1/auth/token</code> with the OAuth 2.0 client-credentials grant, limited to the
        read scopes chosen here. <strong>Webhooks</strong> tell a system when something happens, such as a voucher being posted; the
        message names the event and the document, and the system reads the details through the API. Messages are signed with the
        subscription's secret.</p>
      {secret && <SecretOnce label={secret.label} value={secret.value} onDone={() => setSecret(null)} />}
      <ErrorAlert error={catalog.error ?? clients.error ?? hooks.error ?? rotateClient.error ?? setStatus.error ?? test.error ?? redeliver.error} />

      <h2 className="mb-2 text-base font-semibold text-slate-900">API clients</h2>
      {canManage && catalog.data && (
        <form className={`${card} max-w-3xl`} onSubmit={(e: FormEvent) => { e.preventDefault(); createClient.mutate(); }}>
          <label className="block text-sm">Client name<input value={clientName} maxLength={120} onChange={(e) => setClientName(e.target.value)}
            placeholder="For example: FMIS budget reader" className={`mt-1 ${input}`} /></label>
          <Checkboxes legend="Scopes (read only)" options={catalog.data.scopes} value={scopes} onChange={setScopes} />
          <ErrorAlert error={createClient.error} />
          <Button type="submit" busy={createClient.isPending} disabled={clientName.trim().length < 3 || scopes.length === 0}>Create client</Button>
        </form>
      )}
      {clients.isPending ? <Spinner /> : clients.data && (
        <div className="mb-6 overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className={th}>Client</th><th className={th}>Scopes</th><th className={th}>Status</th><th className={th}>Last used</th><th className={th} /></tr>
            </thead>
            <tbody>
              {clients.data.map((c) => (
                <tr key={c.id} className="border-t border-slate-100 align-top">
                  <td className="px-3 py-2">{c.name}<span className="block font-mono text-xs text-slate-500">{c.clientId}</span></td>
                  <td className="px-3 py-2 text-xs">{c.scopes.map(humanize).join(', ')}</td>
                  <td className="px-3 py-2"><Badge tone={c.status === 'ACTIVE' ? 'green' : 'slate'}>{c.status}</Badge>
                    {c.revokeReason && <span className="block text-xs text-slate-500">{c.revokeReason}</span>}</td>
                  <td className="whitespace-nowrap px-3 py-2 text-xs">{c.lastUsedAt ? formatDateTime(c.lastUsedAt) : 'never'}</td>
                  <td className="whitespace-nowrap px-3 py-2 text-right">{canManage && c.status === 'ACTIVE' && <div className="flex justify-end gap-1">
                    <Button variant="secondary" busy={rotateClient.isPending} onClick={() => rotateClient.mutate(c)}>New secret</Button>
                    <Button variant="secondary" onClick={() => { revoke.reset(); setRevoking(c); }}>Revoke</Button></div>}</td>
                </tr>
              ))}
              {clients.data.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-slate-500">No API clients.</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      <h2 className="mb-2 text-base font-semibold text-slate-900">Webhooks</h2>
      {canManage && catalog.data && (
        <form className={`${card} max-w-3xl`} onSubmit={(e: FormEvent) => { e.preventDefault(); subscribe.mutate(); }}>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="block text-sm">Name<input value={hookName} maxLength={120} onChange={(e) => setHookName(e.target.value)} className={`mt-1 ${input}`} /></label>
            <label className="block text-sm">URL (HTTPS)<input value={url} maxLength={500} onChange={(e) => setUrl(e.target.value)}
              placeholder="https://receiver.example.gov.ph/cas" className={`mt-1 ${input}`} /></label>
          </div>
          <Checkboxes legend="Events" options={catalog.data.events} value={events} onChange={setEvents} />
          <ErrorAlert error={subscribe.error} />
          <Button type="submit" busy={subscribe.isPending} disabled={hookName.trim().length < 3 || !url || events.length === 0}>Subscribe</Button>
        </form>
      )}
      {hooks.isPending ? <Spinner /> : hooks.data && (
        <div className="mb-4 overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className={th}>Subscription</th><th className={th}>Events</th><th className={th}>Status</th><th className={th} /></tr>
            </thead>
            <tbody>
              {hooks.data.map((w) => (
                <tr key={w.id} className="border-t border-slate-100 align-top">
                  <td className="max-w-xs px-3 py-2">{w.name}<span className="block break-all font-mono text-xs text-slate-500">{w.url}</span></td>
                  <td className="px-3 py-2 text-xs">{w.events.map(humanize).join(', ')}</td>
                  <td className="px-3 py-2"><Badge tone={w.status === 'ACTIVE' ? 'green' : w.status === 'DISABLED' ? 'red' : 'slate'}>{w.status}</Badge>
                    {w.consecutiveFailures > 0 && <span className="block text-xs text-red-700">{w.consecutiveFailures} failed in a row</span>}</td>
                  <td className="whitespace-nowrap px-3 py-2 text-right"><div className="flex justify-end gap-1">
                    <Button variant="secondary" onClick={() => setLog(log === w.id ? null : w.id)}>{log === w.id ? 'Hide log' : 'Deliveries'}</Button>
                    {canManage && <>
                      <Button variant="secondary" busy={test.isPending} onClick={() => test.mutate(w)}>Send test</Button>
                      {w.status === 'ACTIVE' ? <Button variant="secondary" onClick={() => setStatus.mutate({ w, s: 'PAUSED' })}>Pause</Button>
                        : <Button variant="secondary" onClick={() => setStatus.mutate({ w, s: 'ACTIVE' })}>Resume</Button>}</>}
                  </div></td>
                </tr>
              ))}
              {hooks.data.length === 0 && <tr><td colSpan={4} className="px-4 py-6 text-center text-slate-500">No webhook subscriptions.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {log && (
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <h3 className="border-b border-slate-200 px-4 py-3 text-sm font-semibold text-slate-800">Deliveries</h3>
          {deliveries.isPending ? <Spinner /> : deliveries.data && (
            <div className="overflow-x-auto"><table className="min-w-full text-left text-sm">
              <thead className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
                <tr><th className={th}>Created</th><th className={th}>Event</th><th className={th}>Status</th><th className={th}>Attempts</th>
                  <th className={th}>Last result</th><th className={th} /></tr>
              </thead>
              <tbody>
                {deliveries.data.items.map((d) => (
                  <tr key={d.id} className="border-t border-slate-100">
                    <td className="whitespace-nowrap px-3 py-1.5 text-xs">{formatDateTime(d.createdAt)}</td>
                    <td className="px-3 py-1.5 text-xs">{humanize(d.eventType)}</td>
                    <td className="px-3 py-1.5"><Badge tone={d.status === 'DELIVERED' ? 'green' : d.status === 'DEAD' ? 'red' : 'amber'}>{d.status}</Badge></td>
                    <td className="px-3 py-1.5 text-xs">{d.attempts}{d.status === 'PENDING' && d.nextAttemptAt && <span className="block text-slate-500">next {formatDateTime(d.nextAttemptAt)}</span>}</td>
                    <td className="px-3 py-1.5 text-xs">{d.lastError ?? (d.lastStatus ? `HTTP ${d.lastStatus}` : '—')}</td>
                    <td className="px-3 py-1.5 text-right">{canManage && d.status === 'DEAD' && <Button variant="secondary" onClick={() => redeliver.mutate(d.id)}>Send again</Button>}</td>
                  </tr>
                ))}
                {deliveries.data.items.length === 0 && <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-500">No deliveries yet.</td></tr>}
              </tbody>
            </table></div>
          )}
        </section>
      )}
      <ActionDialog spec={revokeSpec} busy={revoke.isPending} error={revoke.error} onConfirm={(r) => revoke.mutate(r)} onClose={() => setRevoking(null)} />
    </>
  );
}
