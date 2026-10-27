import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { migrationApi } from '../../api/endpoints';
import type { MigrationBatchDetail, MigrationKind, MigrationRow } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ConfirmButton } from '../../components/ConfirmButton';
import { DocumentsPanel } from '../../components/DocumentsPanel';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';

const KINDS: { value: MigrationKind; label: string }[] = [
  { value: 'GL_OPENING', label: 'GL opening trial balance' },
  { value: 'AR_OPEN_ITEMS', label: 'Open customer invoices (AR)' },
  { value: 'AP_OPEN_ITEMS', label: 'Unpaid supplier invoices (AP)' },
  { value: 'AR_UNAPPLIED', label: 'Unapplied customer collections' },
  { value: 'AP_ADVANCES', label: 'Supplier advances' },
  { value: 'FA_REGISTER', label: 'Fixed-asset register' },
  { value: 'INVENTORY_STOCK', label: 'Stock on hand' },
];
const kindLabel = (k: string) => KINDS.find((x) => x.value === k)?.label ?? k;
const byParty = (k: string) => ['AR_OPEN_ITEMS', 'AP_OPEN_ITEMS', 'AR_UNAPPLIED', 'AP_ADVANCES'].includes(k);

export function MigrationBatchesPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [filter, setFilter] = useState('');
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['migration-batches', filter, page], queryFn: () => migrationApi.list(filter || undefined, page),
    placeholderData: keepPreviousData });
  const [form, setForm] = useState<{ kind: MigrationKind; description: string; cutoverDate: string; openingEntryNumber: string }>(
    { kind: 'GL_OPENING', description: '', cutoverDate: '', openingEntryNumber: '' });
  const [file, setFile] = useState<File | null>(null);
  const gl = form.kind === 'GL_OPENING';
  const upload = useMutation({
    mutationFn: () => migrationApi.upload(file!, { kind: form.kind, description: form.description,
      cutoverDate: gl ? form.cutoverDate : undefined, openingEntryNumber: gl ? undefined : form.openingEntryNumber }),
    onSuccess: (b) => { qc.invalidateQueries({ queryKey: ['migration-batches'] }); navigate(`/migration/${b.summary.id}`); },
  });
  const valid = file && form.description.trim() && (gl ? form.cutoverDate : form.openingEntryNumber.trim());
  return (
    <>
      <PageHeader title="Opening balances" crumbs={[{ label: 'Migration' }, { label: 'Opening balances' }]} />
      <p className="mb-4 text-sm text-slate-600">Load balances at cutover from CAS templates. The opening trial balance becomes a draft opening journal
        voucher that is approved and posted as usual. Everything else (open invoices, unapplied collections, supplier advances, the fixed-asset register
        and stock on hand) is then tied to the posted opening entry, must agree with it customer by customer, supplier by supplier or account by account,
        and is applied by a second person. Nothing is posted twice. Mapping each legacy extract onto these templates is part of the migration plan
        (clarification C-12).</p>
      {hasPermission('MIGRATION_PREPARE') && (
        <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <h2 className="font-semibold">Upload a batch</h2>
          <ErrorAlert error={upload.error} />
          <div className="grid gap-3 md:grid-cols-3">
            <div className="space-y-1.5"><label htmlFor="mkind" className="block text-sm font-medium text-slate-700">Batch type</label>
              <select id="mkind" value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value as MigrationKind })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
                {KINDS.map((k) => <option key={k.value} value={k.value}>{k.label}</option>)}</select></div>
            {gl ? <Field label="Cutover date" type="date" value={form.cutoverDate} onChange={(e) => setForm({ ...form, cutoverDate: e.target.value })}
              hint="First period of the fiscal year the balances open" />
              : <Field label="Opening entry number" value={form.openingEntryNumber} onChange={(e) => setForm({ ...form, openingEntryNumber: e.target.value })}
                hint="The posted opening entry these items belong to" />}
            <Field label="Batch description" value={form.description} maxLength={500} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            <div className="space-y-1 md:col-span-3"><label htmlFor="mfile" className="block text-sm font-medium text-slate-700">CSV file</label>
              <input id="mfile" type="file" accept=".csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="text-sm" /></div>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <Button busy={upload.isPending} disabled={!valid} onClick={() => upload.mutate()}>Upload and validate</Button>
            <button type="button" className="text-sm text-brand-700 hover:underline" onClick={() => migrationApi.template(form.kind)}>Download the empty template</button>
          </div>
        </section>
      )}
      <select aria-label="Filter batch type" value={filter} onChange={(e) => { setFilter(e.target.value); setPage(0); }} className="mb-3 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
        <option value="">All batch types</option>{KINDS.map((k) => <option key={k.value} value={k.value}>{k.label}</option>)}</select>
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Batch</th><th className="px-3 py-2">Type</th>
              <th className="px-3 py-2">Status</th><th className="px-3 py-2">Cutover</th><th className="px-3 py-2">Rows (errors)</th>
              <th className="px-3 py-2 text-right">Total</th><th className="px-3 py-2">Uploaded</th><th className="px-3 py-2">Applied</th></tr></thead>
            <tbody>{(list.data?.items ?? []).map((b) => (
              <tr key={b.id} className="border-t border-slate-100">
                <td className="px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/migration/${b.id}`}>{b.description}</Link>
                  <div className="text-xs text-slate-500">{b.fileName}</div></td>
                <td className="px-3 py-2">{kindLabel(b.kind)}</td><td className="px-3 py-2"><StatusBadge status={b.status} /></td>
                <td className="px-3 py-2">{b.cutoverDate}</td><td className="px-3 py-2">{b.rowCount} ({b.errorCount})</td>
                <td className="px-3 py-2 text-right"><Money value={b.totalAmount} /></td>
                <td className="px-3 py-2">{b.uploadedBy}, {formatDateTime(b.uploadedAt)}</td>
                <td className="px-3 py-2">{b.appliedBy ? `${b.appliedBy}, ${formatDateTime(b.appliedAt)}` : '—'}</td></tr>))}
              {list.data?.items.length === 0 && <tr><td colSpan={8} className="px-4 py-6 text-center text-slate-500">No batches yet.</td></tr>}</tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}

function RowsTable({ title, columns, rows }: { title: string; columns: string[]; rows: MigrationRow[] }) {
  if (rows.length === 0) {
    return null;
  }
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <h2 className="mb-3 font-semibold">{title}</h2>
      <div className="overflow-x-auto"><table className="min-w-full text-xs">
        <thead className="border-b border-slate-200 text-left font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-2 py-1">Line</th>{columns.map((c) => <th key={c} className="px-2 py-1">{c}</th>)}
          <th className="px-2 py-1">Problems</th></tr></thead>
        <tbody>{rows.map((r) => (
          <tr key={r.rowNo} className="border-t border-slate-100 align-top"><td className="px-2 py-1">{r.rowNo}</td>
            {columns.map((c) => <td key={c} className="px-2 py-1 font-mono">{r.data[c]}</td>)}
            <td className="px-2 py-1 text-red-700">{r.errors.join(' ')}</td></tr>))}</tbody>
      </table></div>
    </section>
  );
}

export function MigrationBatchPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['migration-batch', id], queryFn: () => migrationApi.get(id) });
  const [reason, setReason] = useState('');
  const done = (b: MigrationBatchDetail) => { qc.setQueryData(['migration-batch', id], b); qc.invalidateQueries({ queryKey: ['migration-batches'] }); };
  const apply = useMutation({ mutationFn: () => migrationApi.apply(q.data!.summary), onSuccess: done });
  const cancel = useMutation({ mutationFn: () => migrationApi.cancel(q.data!.summary, reason), onSuccess: done });
  const attach = useMutation({ mutationFn: (f: File) => migrationApi.attach(id, f), onSuccess: () => qc.invalidateQueries({ queryKey: ['migration-batch', id] }) });
  if (q.isPending) {
    return <Spinner />;
  }
  if (!q.data) {
    return <ErrorAlert error={q.error} />;
  }
  const { summary: s, reconciliation: rec, availableActions: actions } = q.data;
  return (
    <>
      <PageHeader title={s.description} crumbs={[{ label: 'Migration' }, { label: 'Opening balances', to: '/migration' }, { label: s.fileName }]} />
      <ErrorAlert error={apply.error ?? cancel.error} />
      <section className="mb-4 grid gap-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm md:grid-cols-4">
        <div><div className="text-xs text-slate-500">Type</div>{kindLabel(s.kind)}</div>
        <div><div className="text-xs text-slate-500">Status</div><StatusBadge status={s.status} /></div>
        <div><div className="text-xs text-slate-500">Cutover</div>{s.cutoverDate}{s.openingEntryNumber && ` · entry ${s.openingEntryNumber}`}</div>
        <div><div className="text-xs text-slate-500">{s.kind === 'GL_OPENING' ? 'Total debits' : s.kind === 'FA_REGISTER' ? 'Total cost'
          : s.kind === 'INVENTORY_STOCK' ? 'Total value' : 'Total outstanding'}</div><Money value={s.totalAmount} /></div>
        <div><div className="text-xs text-slate-500">Rows</div>{s.rowCount} ({s.errorCount} with problems)</div>
        <div><div className="text-xs text-slate-500">Uploaded</div>{s.uploadedBy}, {formatDateTime(s.uploadedAt)}</div>
        <div><div className="text-xs text-slate-500">Applied</div>{s.appliedBy ? `${s.appliedBy}, ${formatDateTime(s.appliedAt)}` : '—'}</div>
        <div className="break-all"><div className="text-xs text-slate-500">File SHA-256</div><span className="font-mono text-xs">{s.fileSha256}</span></div>
        {s.journalVoucherId && <div className="md:col-span-4">Opening voucher: <Link className="text-brand-700 hover:underline"
          to={`/journal-vouchers/${s.journalVoucherId}`}>{s.journalVoucherNumber ?? 'draft'}</Link> <StatusBadge status={s.journalVoucherStatus ?? ''} />
          <span className="ml-2 text-xs text-slate-500">Submit, approve and post it through the journal workflow; then load open items against its entry.</span></div>}
        {s.cancelReason && <div className="md:col-span-4">Cancelled: {s.cancelReason}</div>}
      </section>
      {q.data.batchErrors.length > 0 && <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {q.data.batchErrors.map((e) => <p key={e}>{e}</p>)}</div>}
      {rec && (
        <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
          <h2 className="mb-2 font-semibold">Agreement with the opening entry ({rec.controlAccountCode})</h2>
          <p className={rec.reconciled ? 'text-green-800' : 'text-red-700'}>{rec.reconciled
            ? `All ${rec.partiesMatched} ${byParty(s.kind) ? 'parties' : 'accounts'} agree.`
            : `${rec.differences.length} ${byParty(s.kind) ? 'part' + (rec.differences.length === 1 ? 'y differs' : 'ies differ')
              : 'account' + (rec.differences.length === 1 ? ' differs' : 's differ')}; ${rec.partiesMatched} agree.`}
            {' '}Ledger <Money value={rec.ledgerTotal} />, batch <Money value={rec.batchTotal} />.</p>
          {rec.differences.length > 0 && <table className="mt-2 min-w-full text-xs">
            <thead className="border-b border-slate-200 text-left font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-2 py-1">{byParty(s.kind) ? 'Party' : 'Account'}</th><th className="px-2 py-1 text-right">Opening entry</th>
              <th className="px-2 py-1 text-right">Batch</th><th className="px-2 py-1 text-right">Difference</th></tr></thead>
            <tbody>{rec.differences.map((d) => <tr key={d.partyCode} className="border-t border-slate-100"><td className="px-2 py-1">{d.partyCode} {d.partyName}</td>
              <td className="px-2 py-1 text-right"><Money value={d.ledger} /></td><td className="px-2 py-1 text-right"><Money value={d.batch} /></td>
              <td className="px-2 py-1 text-right"><Money value={d.difference} /></td></tr>)}</tbody></table>}
        </section>
      )}
      <div className="mb-4 flex flex-wrap items-end gap-3">
        {actions.includes('CREATE_VOUCHER') && <ConfirmButton label="Create opening voucher" title="Create the opening journal voucher" variant="primary"
          onConfirm={() => apply.mutateAsync()}>You become the voucher's preparer. It is created as a draft with this file attached.</ConfirmButton>}
        {actions.includes('APPLY') && <ConfirmButton label="Apply batch" title="Apply the batch" variant="primary" onConfirm={() => apply.mutateAsync()}>
          {s.rowCount} records are created as posted against entry {s.openingEntryNumber}. Nothing is posted again, and the batch cannot be undone;
          corrections are made with the module's normal documents.</ConfirmButton>}
        {actions.includes('CANCEL') && <>
          <Field label="Cancellation reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          <Button variant="secondary" busy={cancel.isPending} disabled={reason.trim().length < 5} onClick={() => cancel.mutate()}>Cancel batch</Button></>}
      </div>
      <div className="space-y-4">
        <RowsTable title={`Rows with problems${s.errorCount > q.data.errorRows.length ? ` (first ${q.data.errorRows.length})` : ''}`} columns={q.data.columns} rows={q.data.errorRows} />
        <RowsTable title="First rows of the file" columns={q.data.columns} rows={q.data.firstRows} />
        <DocumentsPanel documents={q.data.documents} canUpload={actions.includes('UPLOAD')} onUpload={(f) => attach.mutate(f)} uploading={attach.isPending}
          error={attach.error} requiredNote="the uploaded file is kept; attach legacy reports as evidence" />
      </div>
    </>
  );
}
