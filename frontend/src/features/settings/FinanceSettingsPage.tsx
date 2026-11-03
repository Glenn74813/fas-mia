import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { Combobox } from '../../components/Combobox';
import { useState } from 'react';
import type { BirAtc, TaxCode } from '../../api/types';
import { settingsApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { accountsFor, postableAccounts, useChart } from '../../hooks/useAccounting';

const ATC_SECTIONS = { A: 'Expanded withholding', B: 'Business tax, government or private payor', C: 'Business tax, government payor only' };
const atcLabel = (a: BirAtc) => `${a.atc} — ${a.description}${a.condition ? ` (${a.condition})` : ''}`;

/** The BIR alphanumeric tax code of a withholding code, chosen from the schedule on Form 2307. */
function AtcCell({ taxCode, admin, catalogue }: { taxCode: TaxCode; admin: boolean; catalogue: BirAtc[] }) {
  const qc = useQueryClient();
  const save = useMutation({ mutationFn: (atc: string) => settingsApi.setTaxCodeAtc(taxCode.id, atc),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['tax-codes'] }) });
  if (taxCode.taxType !== 'EWT') return <>—</>;
  if (!admin) return <span className="font-mono">{taxCode.atc ?? '—'}</span>;
  return (
    <>
      <Combobox aria-label={`ATC of ${taxCode.code}`} value={taxCode.atc ?? ''} onChange={(v) => save.mutate(v)} allowEmpty placeholder="None"
        className="w-72 rounded-md border border-slate-300 px-2 py-1 text-sm" options={catalogue.map((a) => ({ value: a.atc, label: atcLabel(a) }))} />
      {save.error ? <ErrorAlert error={save.error} /> : null}
    </>
  );
}

/** The whole schedule, searchable, so the tax team can see what each code covers. */
function AtcCatalogue({ catalogue }: { catalogue: BirAtc[] }) {
  const [q, setQ] = useState('');
  const [section, setSection] = useState('');
  const text = q.trim().toLowerCase();
  const rows = catalogue.filter((a) => (!section || a.section === section)
    && (!text || `${a.atc} ${a.description} ${a.condition ?? ''}`.toLowerCase().includes(text)));
  return (
    <details className="mt-4 border-t border-slate-100 pt-3">
      <summary className="cursor-pointer text-sm font-medium text-brand-700">BIR alphanumeric tax codes ({catalogue.length})</summary>
      <p className="mt-2 text-xs text-slate-600">From the schedule on BIR Form 2307 (January 2018). Rates appear only where the form prints them;
        every other rate is set on the tax code by the tax team.</p>
      <div className="my-2 flex flex-wrap gap-2">
        <input aria-label="Search ATCs" placeholder="Search code or payment, e.g. consultants" value={q} onChange={(e) => setQ(e.target.value)}
          className="w-80 rounded-md border border-slate-300 px-2 py-1 text-sm" />
        <Select aria-label="ATC section" value={section} onChange={(e) => setSection(e.target.value)} className="rounded-md border border-slate-300 px-2 py-1 text-sm">
          <option value="">All sections</option>
          {Object.entries(ATC_SECTIONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select>
      </div>
      <div className="max-h-96 overflow-auto"><table className="min-w-full text-sm">
        <thead className="sticky top-0 border-b border-slate-200 bg-white text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
          <tr><th className="py-1 pr-3">ATC</th><th className="py-1 pr-3">Payee</th><th className="py-1 pr-3">Payment</th><th className="py-1 pr-3">When</th><th className="py-1">Rate on form</th></tr></thead>
        <tbody>{rows.map((a) => (
          <tr key={a.atc} className="border-t border-slate-100 align-top"><td className="py-1.5 pr-3 font-mono">{a.atc}</td>
            <td className="py-1.5 pr-3">{a.payeeType === 'INDIVIDUAL' ? 'Individual' : a.payeeType === 'CORPORATION' ? 'Corporation' : 'Any'}</td>
            <td className="py-1.5 pr-3">{a.description}</td><td className="py-1.5 pr-3">{a.condition ?? '—'}</td>
            <td className="py-1.5">{a.printedRate === null ? '—' : `${(Number(a.printedRate) * 100).toFixed(2)}%`}</td></tr>))}
          {rows.length === 0 && <tr><td colSpan={5} className="py-4 text-center text-slate-500">No code matches.</td></tr>}</tbody>
      </table></div>
    </details>
  );
}

export function FinanceSettingsPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const chart = useChart();
  const accounts = postableAccounts(chart.data?.accounts);
  const mappings = useQuery({ queryKey: ['mappings'], queryFn: settingsApi.mappings });
  const taxCodes = useQuery({ queryKey: ['tax-codes'], queryFn: settingsApi.taxCodes });
  const atcs = useQuery({ queryKey: ['atc-catalogue'], queryFn: settingsApi.atcCatalogue, staleTime: Infinity });
  const banks = useQuery({ queryKey: ['bank-accounts'], queryFn: settingsApi.bankAccounts });
  const setMapping = useMutation({ mutationFn: ({ key, accountId }: { key: string; accountId: string }) => settingsApi.setMapping(key, accountId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['mappings'] }) });
  const [tax, setTax] = useState({ code: '', description: '', taxType: 'EWT', rate: '', accountId: '' });
  const createTax = useMutation({ mutationFn: () => settingsApi.createTaxCode(tax), onSuccess: () => qc.invalidateQueries({ queryKey: ['tax-codes'] }) });
  const [bank, setBank] = useState({ code: '', bankName: '', accountName: '', accountNumberLast4: '', glAccountId: '' });
  const createBank = useMutation({ mutationFn: () => settingsApi.createBankAccount(bank), onSuccess: () => qc.invalidateQueries({ queryKey: ['bank-accounts'] }) });
  const admin = hasPermission('ACCOUNT_ADMINISTER');
  const input = 'rounded-md border border-slate-300 px-2 py-1 text-sm';
  return (
    <>
      <PageHeader title="Finance settings" crumbs={[{ label: 'General ledger' }, { label: 'Finance settings' }]} />
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <h2 className="mb-1 font-semibold">Control-account mappings</h2>
        <p className="mb-3 text-xs text-slate-500">Accounts used by automatic postings from sub-ledgers.</p>
        <ErrorAlert error={mappings.error ?? setMapping.error} />
        <ul className="space-y-2 text-sm">{(mappings.data ?? []).map((m) => (
          <li key={m.key} className="flex flex-wrap items-center gap-3"><span className="w-56 font-mono text-xs">{m.key}</span>
            <span className="w-80 text-slate-600">{m.description}</span>
            {admin ? <Combobox aria-label={`${m.key} account`} value={m.accountId ?? ''} className={input}
              onChange={(v) => v && setMapping.mutate({ key: m.key, accountId: v })} placeholder="Not configured"
              options={(m.purpose ? accountsFor(chart.data?.accounts, m.purpose) : accounts).map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} />
              : <span>{m.accountCode ?? <Badge tone="amber">NOT CONFIGURED</Badge>}</span>}</li>))}</ul>
      </section>
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <h2 className="mb-3 font-semibold">Tax codes</h2>
        <ErrorAlert error={createTax.error} />
        <table className="mb-3 min-w-full text-sm"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1">Code</th><th>Type</th>
          <th>Rate</th><th>Account</th><th>ATC</th><th>Description</th></tr></thead>
          <tbody>{(taxCodes.data ?? []).map((t) => (<tr key={t.id} className="border-t border-slate-100"><td className="py-1.5 font-mono">{t.code}</td>
            <td>{t.taxType}</td><td>{(Number(t.rate) * 100).toFixed(2)}%</td><td>{t.accountCode}</td>
            <td className="py-1 pr-2"><AtcCell taxCode={t} admin={admin} catalogue={atcs.data ?? []} /></td>
            <td>{t.description} {t.placeholder && <Badge tone="amber">PLACEHOLDER</Badge>}</td></tr>))}</tbody></table>
        {admin && <div className="flex flex-wrap gap-2">
          <input aria-label="Tax code" placeholder="Code" value={tax.code} onChange={(e) => setTax({ ...tax, code: e.target.value })} className={input} />
          <Select aria-label="Tax type" value={tax.taxType} onChange={(e) => setTax({ ...tax, taxType: e.target.value })} className={input}>
            {['EWT', 'VAT_INPUT', 'VAT_OUTPUT', 'FINAL_VAT'].map((t) => <option key={t}>{t}</option>)}</Select>
          <input aria-label="Rate" placeholder="Rate e.g. 0.0200" value={tax.rate} onChange={(e) => setTax({ ...tax, rate: e.target.value })} className={`${input} w-32`} />
          <Combobox aria-label="Tax account" value={tax.accountId} onChange={(v) => setTax({ ...tax, accountId: v })} className={input} placeholder="Account…"
              options={accountsFor(chart.data?.accounts, tax.taxType === 'VAT_INPUT' ? 'TAX_ASSET' : 'TAX_LIABILITY').map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} />
          <input aria-label="Tax description" placeholder="Description" value={tax.description} onChange={(e) => setTax({ ...tax, description: e.target.value })} className={`${input} w-72`} />
          <Button busy={createTax.isPending} disabled={!tax.code || !tax.rate || !tax.accountId || !tax.description} onClick={() => createTax.mutate()}>Add</Button></div>}
        <AtcCatalogue catalogue={atcs.data ?? []} />
      </section>
      <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <h2 className="mb-3 font-semibold">Bank accounts</h2>
        <ErrorAlert error={createBank.error} />
        <table className="mb-3 min-w-full text-sm"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1">Code</th><th>Bank</th>
          <th>Account</th><th>GL account</th></tr></thead>
          <tbody>{(banks.data ?? []).map((b) => (<tr key={b.id} className="border-t border-slate-100"><td className="py-1.5 font-mono">{b.code}</td>
            <td>{b.bankName}</td><td>{b.accountName} ****{b.accountNumberLast4}</td><td>{b.glAccountCode}</td></tr>))}</tbody></table>
        {hasPermission('BANK_ACCOUNT_MANAGE') && <div className="flex flex-wrap gap-2">
          <input aria-label="Bank account code" placeholder="Code" value={bank.code} onChange={(e) => setBank({ ...bank, code: e.target.value })} className={input} />
          <input aria-label="Bank name" placeholder="Bank" value={bank.bankName} onChange={(e) => setBank({ ...bank, bankName: e.target.value })} className={input} />
          <input aria-label="Account name" placeholder="Account name" value={bank.accountName} onChange={(e) => setBank({ ...bank, accountName: e.target.value })} className={input} />
          <input aria-label="Last four digits" placeholder="Last 4" maxLength={4} value={bank.accountNumberLast4}
            onChange={(e) => setBank({ ...bank, accountNumberLast4: e.target.value.replace(/\D/g, '') })} className={`${input} w-20`} />
          <Combobox aria-label="Cash GL account" value={bank.glAccountId} onChange={(v) => setBank({ ...bank, glAccountId: v })} className={input} placeholder="Cash account…"
              options={accountsFor(chart.data?.accounts, 'CASH').map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} />
          <Button busy={createBank.isPending} disabled={!bank.code || bank.accountNumberLast4.length !== 4 || !bank.glAccountId} onClick={() => createBank.mutate()}>Add</Button></div>}
      </section>
    </>
  );
}
