import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { interfaceApi } from '../../api/endpoints';
import type { InterfaceBatch, InterfaceType } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ActionDialog, type ActionSpec } from '../../components/ActionDialog';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { formatDateTime } from '../../utils/format';

const TYPES: Record<InterfaceType, { label: string; permission: string; header: { name: string; label: string; placeholder: string }[];
  columns: string; result: (id: string) => string }> = {
  PAYROLL: {
    label: 'Payroll summary', permission: 'JOURNAL_CREATE',
    header: [{ name: 'period', label: 'Payroll period', placeholder: 'YYYY-MM' }],
    columns: 'account_code, cost_center_code, description, debit, credit',
    result: (id) => `/journal-vouchers/${id}`,
  },
  FMIS_BUDGET: {
    label: 'FMIS budget', permission: 'BUDGET_PREPARE',
    header: [{ name: 'fiscalYear', label: 'Fiscal year', placeholder: 'YYYY' }],
    columns: 'account_code, cost_center_code, amount',
    result: (id) => `/budgets/${id}`,
  },
  ASSETS: {
    label: 'Asset information', permission: 'FA_MANAGE',
    header: [{ name: 'offsetAccountCode', label: 'Offset account code', placeholder: 'Account credited on activation' }],
    columns: 'category_code, description, serial_number, location, custodian, cost_center_code, acquisition_date, in_service_date, cost, '
      + 'residual_value, useful_life_months',
    result: (id) => `/fixed-assets/${id}`,
  },
};
const TONE = { VALIDATED: 'blue', INVALID: 'red', ACCEPTED: 'green', REJECTED: 'slate' } as const;

export function InboundFilesPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const allowed = (Object.keys(TYPES) as InterfaceType[]).filter((t) => hasPermission(TYPES[t].permission));
  const [type, setType] = useState<InterfaceType>(allowed[0] ?? 'PAYROLL');
  const [file, setFile] = useState<File | null>(null);
  const [reference, setReference] = useState('');
  const [headerValue, setHeaderValue] = useState('');
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<InterfaceBatch | null>(null);

  const batches = useQuery({ queryKey: ['interfaces', page], queryFn: () => interfaceApi.list({ page }), placeholderData: keepPreviousData });
  const detail = useQuery({ queryKey: ['interfaces', 'detail', open], queryFn: () => interfaceApi.get(open!), enabled: !!open });
  const refresh = () => qc.invalidateQueries({ queryKey: ['interfaces'] });
  const upload = useMutation({
    mutationFn: () => interfaceApi.upload(type, file!, { sourceReference: reference, [TYPES[type].header[0]!.name]: headerValue }),
    onSuccess: (b) => { setFile(null); setReference(''); setHeaderValue(''); setOpen(b.id); refresh(); },
  });
  const accept = useMutation({ mutationFn: interfaceApi.accept, onSuccess: refresh });
  const reject = useMutation({
    mutationFn: (note: string) => interfaceApi.reject(rejecting!, note),
    onSuccess: () => { setRejecting(null); refresh(); },
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    upload.mutate();
  };
  const rejectSpec: ActionSpec | null = rejecting && { title: 'Reject the batch', text: 'Nothing is created. The sender can send a corrected file.',
    confirm: 'Reject', commentRequired: true, commentLabel: 'Reason', danger: true };
  const input = 'block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-xs';
  const b = detail.data;
  const columns = b ? Array.from(new Set(b.rows.flatMap((r) => Object.keys(r)))) : [];

  return (
    <>
      <PageHeader title="Inbound files" crumbs={[{ label: 'Administration' }, { label: 'Inbound files' }]} />
      <p className="mb-4 max-w-3xl text-sm text-slate-600">Payroll summaries, FMIS budget data and asset information from other systems,
        as CSV, JSON or XML. Each file is checked when it arrives; accepting it creates <strong>drafts</strong> (a journal voucher,
        budget allocations or fixed assets) that go through the usual review and approval. Nothing is posted by an import. Other
        systems can send files through the API. The field names are proposals until MIAA confirms each system's layout (C-37).</p>
      {allowed.length > 0 && (
        <form onSubmit={submit} className="mb-6 max-w-3xl space-y-3 rounded-xl border border-slate-200/80 bg-white p-4 shadow-xs">
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">Interface
              <select value={type} onChange={(e) => { setType(e.target.value as InterfaceType); setHeaderValue(''); }} className={`mt-1.5 ${input}`}>
                {allowed.map((t) => <option key={t} value={t}>{TYPES[t].label}</option>)}
              </select></label>
            <label className="text-sm">File (.csv, .json or .xml)
              <input type="file" accept=".csv,.json,.xml" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="mt-1.5 block w-full text-sm" /></label>
            <label className="text-sm">Reference
              <input value={reference} maxLength={100} onChange={(e) => setReference(e.target.value)} placeholder="The sending system's batch number"
                className={`mt-1.5 ${input}`} /></label>
            {TYPES[type].header.map((h) => (
              <label key={h.name} className="text-sm">{h.label}
                <input value={headerValue} onChange={(e) => setHeaderValue(e.target.value)} placeholder={h.placeholder} className={`mt-1.5 ${input}`} /></label>
            ))}
          </div>
          <p className="text-xs text-slate-500">CSV columns: <span className="font-mono">{TYPES[type].columns}</span>. In JSON and XML the same names
            appear as fields of each line; the reference and {TYPES[type].header[0]!.label.toLowerCase()} may be in the file instead.</p>
          <ErrorAlert error={upload.error} />
          <Button type="submit" busy={upload.isPending} disabled={!file}>Check file</Button>
        </form>
      )}

      <ErrorAlert error={batches.error ?? accept.error} />
      {batches.isPending ? <Spinner /> : batches.data && (
        <div className="mb-4 overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-3 py-2">Received</th><th className="px-3 py-2">Interface</th><th className="px-3 py-2">Reference</th>
                <th className="px-3 py-2">Rows</th><th className="px-3 py-2">Status</th><th className="px-3 py-2" /></tr>
            </thead>
            <tbody>
              {batches.data.items.map((x) => (
                <tr key={x.id} className={`border-t border-slate-100 align-top ${open === x.id ? 'bg-brand-50' : ''}`}>
                  <td className="whitespace-nowrap px-3 py-2">{formatDateTime(x.receivedAt)}<span className="block text-xs text-slate-500">{x.receivedBy}</span></td>
                  <td className="px-3 py-2">{TYPES[x.interfaceType].label}<span className="block text-xs text-slate-500">{x.format}</span></td>
                  <td className="px-3 py-2">{x.sourceReference}</td>
                  <td className="px-3 py-2">{x.rowCount}</td>
                  <td className="px-3 py-2"><Badge tone={TONE[x.status]}>{x.status}</Badge>
                    {x.decidedBy && <span className="block text-xs text-slate-500">{x.decidedBy}</span>}</td>
                  <td className="whitespace-nowrap px-3 py-2 text-right"><div className="flex justify-end gap-1">
                    <Button variant="secondary" onClick={() => setOpen(open === x.id ? null : x.id)}>{open === x.id ? 'Close' : 'Open'}</Button>
                    {x.status === 'VALIDATED' && hasPermission(TYPES[x.interfaceType].permission) &&
                      <Button busy={accept.isPending} onClick={() => accept.mutate(x)}>Create drafts</Button>}
                    {(x.status === 'VALIDATED' || x.status === 'INVALID') && hasPermission(TYPES[x.interfaceType].permission) &&
                      <Button variant="secondary" onClick={() => { reject.reset(); setRejecting(x); }}>Reject</Button>}
                  </div></td>
                </tr>
              ))}
              {batches.data.items.length === 0 && <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-500">No files received.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {batches.data && <Pagination page={batches.data.page} totalPages={batches.data.totalPages} totalItems={batches.data.totalItems} onPage={setPage} />}

      {open && (
        <section className="mt-4 rounded-xl border border-slate-200/80 bg-white p-4 shadow-xs">
          {detail.isPending ? <Spinner /> : b && (
            <>
              <h2 className="mb-2 text-base font-semibold text-slate-900">{TYPES[b.interfaceType].label} {b.sourceReference}</h2>
              <p className="mb-2 text-xs text-slate-500">SHA-256 <span className="font-mono">{b.fileSha256}</span>
                {Object.entries(b.header).map(([k, v]) => <span key={k}> · {k} {v}</span>)}</p>
              {b.errors.length > 0 && (
                <div role="alert" className="mb-3 rounded-md border border-red-300 bg-red-50 p-3 text-sm text-red-800">
                  <p className="font-medium">Problems found ({b.errors.length})</p>
                  <ul className="mt-1 list-disc pl-5">{b.errors.map((e) => <li key={e}>{e}</li>)}</ul>
                </div>
              )}
              {b.resultIds.length > 0 && (
                <p className="mb-3 text-sm">Drafts created: {b.resultIds.map((id, i) => (
                  <Link key={id} to={TYPES[b.interfaceType].result(id)} className="mr-2 text-brand-700 hover:underline">{i + 1}</Link>))}</p>
              )}
              {b.decisionNote && <p className="mb-3 text-sm text-slate-700">Rejected: {b.decisionNote}</p>}
              <div className="overflow-x-auto">
                <table className="min-w-full text-left text-xs">
                  <thead className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
                    <tr><th className="px-2 py-1">#</th>{columns.map((c) => <th key={c} className="px-2 py-1">{c}</th>)}</tr>
                  </thead>
                  <tbody>
                    {b.rows.slice(0, 200).map((r, i) => (
                      <tr key={i} className="border-t border-slate-100"><td className="px-2 py-1 text-slate-500">{i + 1}</td>
                        {columns.map((c) => <td key={c} className="px-2 py-1">{r[c] ?? ''}</td>)}</tr>))}
                  </tbody>
                </table>
                {b.rows.length > 200 && <p className="mt-1 text-xs text-slate-500">Showing the first 200 of {b.rows.length} rows.</p>}
              </div>
            </>
          )}
        </section>
      )}
      <ActionDialog spec={rejectSpec} busy={reject.isPending} error={reject.error} onConfirm={(n) => reject.mutate(n)} onClose={() => setRejecting(null)} />
    </>
  );
}
