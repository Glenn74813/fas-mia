import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { payrollApi } from '../../api/endpoints';
import type { PayrollColumnMapping, PayrollPreview, PayrollTreatment } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Combobox } from '../../components/Combobox';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Select } from '../../components/Select';
import { StatusBadge } from '../../components/StatusBadge';
import { activeCostCenters, postableAccounts, useChart, useCostCenters } from '../../hooks/useAccounting';
import { formatDateTime } from '../../utils/format';

const TREATMENT: Record<PayrollTreatment, string> = {
  DEBIT: 'Debit', CREDIT: 'Credit', IGNORE: 'Ignore', EMPLOYEE_NUMBER: 'Employee number', EMPLOYEE_NAME: 'Employee name',
};
const box = 'mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-xs';

/**
 * Books the HRIS General Payroll (one row per employee, one column per earning or deduction) as a draft journal voucher,
 * through the column-to-account mapping below (TOR §4.6.2; C-37).
 */
export function GeneralPayrollPage() {
  const { hasPermission } = useAuth();
  const canImport = hasPermission('JOURNAL_CREATE');
  const qc = useQueryClient();
  const costCenters = useCostCenters();
  const imports = useQuery({ queryKey: ['payroll-imports'], queryFn: payrollApi.imports });
  const fileRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [form, setForm] = useState({ accountingDate: new Date().toISOString().slice(0, 10), costCenterId: '', reference: '' });
  const [preview, setPreview] = useState<PayrollPreview | null>(null);
  const fields = () => {
    const f: Record<string, string> = { accountingDate: form.accountingDate };
    if (form.costCenterId) f.costCenterId = form.costCenterId;
    if (form.reference.trim()) f.reference = form.reference.trim();
    return f;
  };
  const check = useMutation({ mutationFn: () => payrollApi.preview(file!, fields()), onSuccess: setPreview });
  const book = useMutation({
    mutationFn: () => payrollApi.importFile(file!, fields()),
    onSuccess: () => { setPreview(null); qc.invalidateQueries({ queryKey: ['payroll-imports'] }); },
  });
  const changed = (patch: Partial<typeof form>) => { setForm({ ...form, ...patch }); setPreview(null); book.reset(); };

  return (
    <>
      <PageHeader title="General payroll" crumbs={[{ label: 'General ledger' }, { label: 'General payroll' }]} />
      <p className="mb-4 max-w-3xl text-sm text-slate-600">
        Books the General Payroll exported by the HRIS as one draft journal voucher, with the file attached. Each column is booked
        through the column mapping at the bottom of the page. Preview first: nothing is saved until you click Import.
      </p>

      {canImport && (
        <section className="mb-4 rounded-xl border border-slate-200/80 bg-white p-5 shadow-xs">
          <h2 className="mb-3 font-semibold">Import a General Payroll</h2>
          <div className="grid gap-3 md:grid-cols-4 md:items-end">
            <label className="block text-sm font-medium text-slate-700">File (.xlsx or .csv)
              <input ref={fileRef} type="file" accept=".xlsx,.csv" className="mt-1.5 block w-full text-sm"
                onChange={(e) => { setFile(e.target.files?.[0] ?? null); setPreview(null); book.reset(); }} /></label>
            <Field label="Accounting date" type="date" value={form.accountingDate} onChange={(e) => changed({ accountingDate: e.target.value })} />
            <label className="block text-sm font-medium text-slate-700">Cost centre
              <Combobox value={form.costCenterId} onChange={(v) => changed({ costCenterId: v })} className={box} placeholder="Choose…"
                options={activeCostCenters(costCenters.data).map((c) => ({ value: c.id, label: `${c.code} — ${c.name}` }))} /></label>
            <Field label="Payroll reference" hint="e.g. PR-202611-02; the file name if blank" value={form.reference} maxLength={60}
              onChange={(e) => changed({ reference: e.target.value })} />
          </div>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button variant="secondary" busy={check.isPending} disabled={!file || !form.accountingDate} onClick={() => { book.reset(); check.mutate(); }}>Preview</Button>
            <Button busy={book.isPending} disabled={!preview || preview.problems.length > 0} onClick={() => book.mutate()}>Import as draft voucher</Button>
          </div>
          <div className="mt-3"><ErrorAlert error={check.error ?? book.error} /></div>
          {book.data && (
            <p role="status" className="mt-3 rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-800">
              {book.data.description} was created as a draft journal voucher. <Link className="font-medium underline"
                to={`/journal-vouchers/${book.data.journalVoucherId}`}>Open it</Link> to review and submit it.</p>
          )}
          {preview && <PreviewPanel preview={preview} />}
        </section>
      )}

      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white p-5 shadow-xs">
        <h2 className="mb-3 font-semibold">Imported payrolls</h2>
        <ErrorAlert error={imports.error} />
        <div className="overflow-x-auto"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            <tr><th className="py-1 pr-3">Reference</th><th className="py-1 pr-3">File</th><th className="py-1 pr-3 text-right">Employees</th>
              <th className="py-1 pr-3 text-right">Total</th><th className="py-1 pr-3">Voucher</th><th className="py-1 pr-3">Imported</th></tr></thead>
          <tbody>{(imports.data ?? []).map((i) => (
            <tr key={i.id} className="border-t border-slate-100">
              <td className="py-1.5 pr-3">{i.reference}</td><td className="py-1.5 pr-3 text-slate-600">{i.fileName}</td>
              <td className="py-1.5 pr-3 text-right">{i.employees}</td><td className="py-1.5 pr-3 text-right"><Money value={i.total} /></td>
              <td className="py-1.5 pr-3"><Link className="text-brand-700 hover:underline" to={`/journal-vouchers/${i.journalVoucherId}`}>
                {i.voucherNumber ?? 'Draft'}</Link> <StatusBadge status={i.voucherStatus} /></td>
              <td className="py-1.5 pr-3 text-slate-600">{formatDateTime(i.importedAt)} by {i.importedBy}</td></tr>))}
            {imports.data?.length === 0 && <tr><td colSpan={6} className="py-3 text-center text-slate-500">No payroll imported yet.</td></tr>}</tbody>
        </table></div>
      </section>

      <MappingSection />
    </>
  );
}

function PreviewPanel({ preview }: { preview: PayrollPreview }) {
  return (
    <div className="mt-4 space-y-4">
      {preview.problems.length > 0 ? (
        <div className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
          <p className="mb-1 font-medium">Fix these before importing:</p>
          <ul className="list-disc space-y-0.5 pl-5">{preview.problems.map((p) => <li key={p}>{p}</li>)}</ul>
        </div>
      ) : (
        <p className="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-800">
          Ready to import: {preview.employees} employees, debits and credits of <Money value={preview.totalDebit} /> each.</p>
      )}
      <div className="grid gap-4 lg:grid-cols-2">
        <div>
          <h3 className="mb-1 text-sm font-semibold">Column totals</h3>
          <table className="min-w-full text-sm"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            <tr><th className="py-1 pr-3">Column</th><th className="py-1 pr-3">Booked as</th><th className="py-1 pr-3">Account</th><th className="py-1 text-right">Total</th></tr></thead>
            <tbody>{preview.columns.map((c) => (
              <tr key={c.label} className="border-t border-slate-100"><td className="py-1 pr-3">{c.label}</td><td className="py-1 pr-3">{TREATMENT[c.treatment]}</td>
                <td className="py-1 pr-3 font-mono text-xs">{c.accountCode ?? '—'}</td><td className="py-1 text-right"><Money value={c.total} /></td></tr>))}</tbody></table>
        </div>
        <div>
          <h3 className="mb-1 text-sm font-semibold">Voucher lines ({preview.lines.length})</h3>
          <div className="max-h-96 overflow-y-auto"><table className="min-w-full text-sm"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            <tr><th className="py-1 pr-3">Account</th><th className="py-1 pr-3">Employee</th><th className="py-1 pr-3 text-right">Debit</th><th className="py-1 text-right">Credit</th></tr></thead>
            <tbody>{preview.lines.map((l, i) => (
              <tr key={i} className="border-t border-slate-100"><td className="py-1 pr-3"><span className="font-mono text-xs">{l.accountCode}</span> {l.accountName}</td>
                <td className="py-1 pr-3 text-slate-600">{l.employeeNumber ?? ''}</td>
                <td className="py-1 pr-3 text-right">{l.side === 'DEBIT' ? <Money value={l.amount} /> : ''}</td>
                <td className="py-1 text-right">{l.side === 'CREDIT' ? <Money value={l.amount} /> : ''}</td></tr>))}</tbody>
            <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td className="py-1 pr-3" colSpan={2}>Total</td>
              <td className="py-1 pr-3 text-right"><Money value={preview.totalDebit} /></td><td className="py-1 text-right"><Money value={preview.totalCredit} /></td></tr></tfoot>
          </table></div>
        </div>
      </div>
    </div>
  );
}

function MappingSection() {
  const { hasPermission } = useAuth();
  const manage = hasPermission('ACCOUNT_ADMINISTER');
  const qc = useQueryClient();
  const chart = useChart();
  const mappings = useQuery({ queryKey: ['payroll-mappings'], queryFn: payrollApi.mappings });
  const [edit, setEdit] = useState<{ label: string; treatment: PayrollTreatment; accountId: string; version?: number } | null>(null);
  const save = useMutation({
    mutationFn: () => payrollApi.saveMapping({ label: edit!.label.trim(), treatment: edit!.treatment,
      accountId: edit!.treatment === 'DEBIT' || edit!.treatment === 'CREDIT' ? edit!.accountId || null : null, version: edit!.version }),
    onSuccess: () => { setEdit(null); qc.invalidateQueries({ queryKey: ['payroll-mappings'] }); },
  });
  const booked = edit?.treatment === 'DEBIT' || edit?.treatment === 'CREDIT';
  const open = (m: PayrollColumnMapping | null) => {
    save.reset();
    setEdit(m ? { label: m.label, treatment: m.treatment, accountId: m.accountId ?? '', version: m.version } : { label: '', treatment: 'CREDIT', accountId: '' });
  };
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white p-5 shadow-xs">
      <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">Column mapping</h2>
        {manage && !edit && <Button variant="secondary" onClick={() => open(null)}>Add column</Button>}
      </div>
      <p className="mb-3 max-w-3xl text-xs text-slate-500">How each General Payroll column is booked, matched by its heading. Earnings are debited and
        deductions and net pay credited; an account that keeps an employee subsidiary ledger is booked per employee, against the subsidiary
        account whose code is the employee number. Totals, notes and other columns with nothing to book are ignored.</p>
      {edit && (
        <div className="mb-4 space-y-3 rounded-md border border-slate-200 p-4">
          <ErrorAlert error={save.error} />
          <div className="grid gap-3 md:grid-cols-3 md:items-end">
            <Field label="Column heading" value={edit.label} maxLength={100} disabled={edit.version !== undefined}
              onChange={(e) => setEdit({ ...edit, label: e.target.value })} />
            <label className="block text-sm font-medium text-slate-700">Booked as
              <Select value={edit.treatment} className={box} onChange={(e) => setEdit({ ...edit, treatment: e.target.value as PayrollTreatment })}>
                {Object.entries(TREATMENT).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></label>
            {booked && (
              <label className="block text-sm font-medium text-slate-700">Account
                <Combobox value={edit.accountId} onChange={(v) => setEdit({ ...edit, accountId: v })} className={box} placeholder="Choose…"
                  options={postableAccounts(chart.data?.accounts).map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} /></label>)}
          </div>
          <div className="flex gap-2">
            <Button busy={save.isPending} disabled={!edit.label.trim() || (booked && !edit.accountId)} onClick={() => save.mutate()}>Save</Button>
            <Button variant="secondary" onClick={() => setEdit(null)}>Cancel</Button>
          </div>
        </div>
      )}
      <ErrorAlert error={mappings.error} />
      <div className="overflow-x-auto"><table className="min-w-full text-sm">
        <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
          <tr><th className="py-1 pr-3">Column</th><th className="py-1 pr-3">Booked as</th><th className="py-1 pr-3">Account</th><th /></tr></thead>
        <tbody>{(mappings.data ?? []).map((m) => (
          <tr key={m.columnKey} className="border-t border-slate-100">
            <td className="py-1.5 pr-3">{m.label}</td><td className="py-1.5 pr-3">{TREATMENT[m.treatment]}</td>
            <td className="py-1.5 pr-3">{m.accountCode ? <><span className="font-mono text-xs">{m.accountCode}</span> {m.accountName}
              {m.subsidiaryType === 'EMPLOYEE' && <span className="ml-1"><Badge tone="blue">PER EMPLOYEE</Badge></span>}</>
              : (m.treatment === 'DEBIT' || m.treatment === 'CREDIT') ? <Badge tone="amber">NOT SET</Badge> : '—'}</td>
            <td className="py-1.5 text-right">{manage && <button type="button" className="text-xs text-brand-700 hover:underline" onClick={() => open(m)}>Edit</button>}</td>
          </tr>))}</tbody>
      </table></div>
    </section>
  );
}
