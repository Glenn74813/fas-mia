import { useMutation, useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { birApi } from '../../api/endpoints';
import type { BirAlphalist, Sawt, VatListing } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Select } from '../../components/Select';
import { Spinner } from '../../components/Spinner';

type Tab = 'qap' | 'annual' | 'sawt' | 'sls' | 'slp';

const TABS: { key: Tab; label: string; permission: string }[] = [
  { key: 'qap', label: 'QAP (1601-EQ)', permission: 'AP_VIEW' },
  { key: 'annual', label: 'Alphalist (1604-E)', permission: 'AP_VIEW' },
  { key: 'sawt', label: 'SAWT', permission: 'AR_VIEW' },
  { key: 'sls', label: 'Sales (SLS)', permission: 'AR_VIEW' },
  { key: 'slp', label: 'Purchases (SLP)', permission: 'AP_VIEW' },
];

const th = 'py-1 pr-3';
const thr = 'py-1 pl-3 text-right';
const td = 'py-1.5 pr-3';
const tdr = 'py-1.5 pl-3 text-right';
const percent = (rate: string) => `${(Number(rate) * 100).toFixed(2)}%`;

function Table({ head, children, foot }: { head: ReactNode; children: ReactNode; foot?: ReactNode }) {
  return (
    <div className="overflow-x-auto"><table className="min-w-full text-sm">
      <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr>{head}</tr></thead>
      <tbody>{children}</tbody>
      {foot && <tfoot className="border-t-2 border-slate-300 font-medium"><tr>{foot}</tr></tfoot>}
    </table></div>
  );
}

function Empty({ cols, text }: { cols: number; text: string }) {
  return <tr><td colSpan={cols} className="py-4 text-center text-slate-500">{text}</td></tr>;
}

/** The QAP of a quarter or the 1604-E alphalist of a year, with what each payee still needs before the DAT file can be made. */
function AlphalistView({ list, onFile, busy }: { list: BirAlphalist; onFile: (kind: 'dat' | 'xlsx') => void; busy: boolean }) {
  const incomplete = list.rows.filter((r) => r.missing.length > 0).length;
  return (
    <>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm">
          {list.rows.length === 0 ? <Badge tone="slate">NO WITHHOLDING</Badge> : list.ready ? <Badge tone="green">READY TO FILE</Badge>
            : <Badge tone="amber">DETAILS MISSING</Badge>}
          {list.fileName && <span className="ml-2 text-slate-600">File name <span className="font-mono">{list.fileName}</span></span>}
        </div>
        <span className="flex gap-2">
          <Button disabled={!list.ready} busy={busy} onClick={() => onFile('dat')}>Download DAT file</Button>
          <Button variant="secondary" disabled={list.rows.length === 0} onClick={() => onFile('xlsx')}>Excel</Button>
        </span>
      </div>
      {list.payorMissing.length > 0 && <p className="mb-2 text-xs text-amber-800">Administration › Organisation still needs the {list.payorMissing.join(', ')}.</p>}
      {incomplete > 0 && <p className="mb-2 text-xs text-amber-800">{incomplete} payee row{incomplete === 1 ? '' : 's'} still need details: complete them under
        Payables › Suppliers, or set the ATC of the tax code in Finance settings.</p>}
      <Table head={<><th className={th}>Seq</th><th className={th}>TIN</th><th className={th}>Payee</th><th className={th}>ATC</th><th className={thr}>Rate</th>
        <th className={thr}>Income payment</th><th className={thr}>Tax withheld</th><th className="py-1 pl-3">Still needed</th></>}
        foot={list.rows.length > 0 && <><td className="py-1.5" colSpan={5}>Total</td><td className={tdr}><Money value={list.totalIncomePayment} /></td>
          <td className={tdr}><Money value={list.totalTaxWithheld} /></td><td /></>}>
        {list.rows.map((r) => (
          <tr key={r.sequence} className="border-t border-slate-100">
            <td className={td}>{r.sequence}</td><td className={`${td} font-mono`}>{r.tin ?? '—'}</td>
            <td className={td}>{r.payeeType === 'INDIVIDUAL' ? [r.lastName, r.firstName, r.middleName].filter(Boolean).join(', ') || r.supplierCode : r.registeredName}
              <span className="ml-1 text-xs text-slate-500">{r.supplierCode}</span></td>
            <td className={`${td} font-mono`} title={r.nature ?? undefined}>{r.atc ?? '—'}</td><td className={tdr}>{percent(r.taxRate)}</td>
            <td className={tdr}><Money value={r.incomePayment} /></td><td className={tdr}><Money value={r.taxWithheld} /></td>
            <td className="py-1.5 pl-3 text-xs text-amber-800">{r.missing.join(', ')}</td>
          </tr>))}
        {list.rows.length === 0 && <Empty cols={8} text="No expanded withholding in this period." />}
      </Table>
      <p className="mt-3 text-xs text-slate-500">{list.basis}</p>
    </>
  );
}

function VatView({ list, onFile }: { list: VatListing; onFile: () => void }) {
  const sales = list.list === 'SALES';
  return (
    <>
      <div className="mb-3 flex justify-end"><Button variant="secondary" disabled={list.rows.length === 0} onClick={onFile}>Excel</Button></div>
      <Table head={<><th className={th}>TIN</th><th className={th}>{sales ? 'Customer' : 'Supplier'}</th><th className={thr}>Exempt</th><th className={thr}>Zero-rated</th>
        <th className={thr}>Taxable (net)</th><th className={thr}>{sales ? 'Output VAT' : 'Input VAT'}</th><th className={thr}>Gross</th></>}
        foot={list.rows.length > 0 && <><td className="py-1.5" colSpan={2}>Total</td><td className={tdr}><Money value={list.exempt} /></td>
          <td className={tdr}><Money value={list.zeroRated} /></td><td className={tdr}><Money value={list.taxable} /></td><td className={tdr}><Money value={list.vat} /></td>
          <td className={tdr}><Money value={list.gross} /></td></>}>
        {list.rows.map((r) => (
          <tr key={r.partyId} className="border-t border-slate-100">
            <td className={`${td} font-mono`}>{r.tin ?? <span className="text-amber-800">no TIN</span>}</td><td className={td}>{r.name}</td>
            <td className={tdr}><Money value={r.exempt} blankZero /></td><td className={tdr}><Money value={r.zeroRated} blankZero /></td>
            <td className={tdr}><Money value={r.taxable} blankZero /></td><td className={tdr}><Money value={r.vat} blankZero /></td>
            <td className={tdr}><Money value={r.gross} /></td>
          </tr>))}
        {list.rows.length === 0 && <Empty cols={7} text={sales ? 'No posted sales in this quarter.' : 'No posted purchases in this quarter.'} />}
      </Table>
      <p className="mt-3 text-xs text-slate-500">{list.basis}</p>
    </>
  );
}

/** BIR lists of withholding tax and VAT for a quarter or year: QAP and 1604-E as BIR DAT files, SAWT and SLSP as listings. */
export function BirReportsPage() {
  const { hasPermission } = useAuth();
  const tabs = TABS.filter((t) => hasPermission(t.permission));
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [quarter, setQuarter] = useState(Math.floor(now.getMonth() / 3) + 1);
  const [tab, setTab] = useState<Tab>(tabs[0]?.key ?? 'qap');
  const valid = year >= 2000 && year <= 9999;
  const q = useQuery({
    queryKey: ['bir', tab, year, tab === 'annual' ? null : quarter], enabled: valid && tabs.length > 0,
    queryFn: (): Promise<unknown> => tab === 'qap' ? birApi.qap(year, quarter) : tab === 'annual' ? birApi.annual(year)
      : tab === 'sawt' ? birApi.sawt(year, quarter) : tab === 'sls' ? birApi.sales(year, quarter) : birApi.purchases(year, quarter),
  });
  const file = useMutation({ mutationFn: (path: string) => birApi.file(path, year, tab === 'annual' ? null : quarter, path) });
  const period = tab === 'annual' ? String(year) : `Q${quarter} ${year}`;
  return (
    <>
      <PageHeader title="BIR reports" crumbs={[{ label: 'Reports' }, { label: 'BIR reports' }]} />
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div role="tablist" aria-label="BIR list" className="flex flex-wrap gap-1">
          {tabs.map((t) => <Button key={t.key} role="tab" aria-selected={tab === t.key} variant={tab === t.key ? 'primary' : 'secondary'}
            onClick={() => { file.reset(); setTab(t.key); }}>{t.label}</Button>)}
        </div>
        <span className="flex gap-2 text-sm">
          <input aria-label="Year" inputMode="numeric" value={year} onChange={(e) => setYear(Number(e.target.value.replace(/\D/g, '').slice(0, 4)) || 0)}
            className="w-20 rounded-md border border-slate-300 px-2 py-1" />
          {tab !== 'annual' && <Select aria-label="Quarter" value={quarter} onChange={(e) => setQuarter(Number(e.target.value))}
            className="w-24 rounded-md border border-slate-300 px-2 py-1">{[1, 2, 3, 4].map((n) => <option key={n} value={n}>Q{n}</option>)}</Select>}
        </span>
      </div>
      <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label={`${TABS.find((t) => t.key === tab)?.label} ${period}`}>
        <ErrorAlert error={q.error ?? file.error} />
        {!valid ? <p className="text-sm text-slate-500">Enter a four-digit year.</p> : q.isPending ? <Spinner /> : q.data ? (
          tab === 'qap' || tab === 'annual' ? (
            <AlphalistView list={q.data as BirAlphalist} busy={file.isPending}
              onFile={(kind) => file.mutate(`${tab === 'qap' ? 'qap' : '1604e'}.${kind}`)} />
          ) : tab === 'sawt' ? (() => {
            const s = q.data as Sawt;
            return (
              <>
                <div className="mb-3 flex justify-end"><Button variant="secondary" disabled={s.rows.length === 0} onClick={() => file.mutate('sawt.xlsx')}>Excel</Button></div>
                <Table head={<><th className={th}>Seq</th><th className={th}>TIN</th><th className={th}>Withholding agent (customer)</th><th className={th}>ATC</th>
                  <th className={thr}>Rate</th><th className={thr}>Income payment</th><th className={thr}>Tax withheld</th></>}
                  foot={s.rows.length > 0 && <><td className="py-1.5" colSpan={5}>Total</td><td className={tdr}><Money value={s.totalIncomePayment} /></td>
                    <td className={tdr}><Money value={s.totalTaxWithheld} /></td></>}>
                  {s.rows.map((r) => (
                    <tr key={r.sequence} className="border-t border-slate-100"><td className={td}>{r.sequence}</td>
                      <td className={`${td} font-mono`}>{r.tin ?? <span className="text-amber-800">no TIN</span>}</td><td className={td}>{r.name}</td>
                      <td className={`${td} font-mono`} title={r.nature}>{r.atc}</td><td className={tdr}>{percent(r.taxRate)}</td>
                      <td className={tdr}><Money value={r.incomePayment} /></td><td className={tdr}><Money value={r.taxWithheld} /></td></tr>))}
                  {s.rows.length === 0 && <Empty cols={7} text="No customer certificates recorded for this quarter." />}
                </Table>
                {s.uncertified.length > 0 && (
                  <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm">
                    <p className="mb-2 font-medium text-amber-900">{s.uncertified.length} receipt{s.uncertified.length === 1 ? '' : 's'} with tax withheld have no BIR 2307
                      recorded, so {s.uncertified.length === 1 ? 'it is' : 'they are'} not on the SAWT. Open each receipt and click Record certificate.</p>
                    <ul className="space-y-1">{s.uncertified.map((u) => (
                      <li key={u.receiptId}><Link className="text-brand-700 hover:underline" to={`/receivables/receipts/${u.receiptId}`}>{u.receiptNumber}</Link>
                        {' '}{u.receiptDate} · {u.customerName} · withheld <Money value={u.taxWithheld} /></li>))}</ul>
                  </div>
                )}
                <p className="mt-3 text-xs text-slate-500">{s.basis}</p>
              </>
            );
          })() : <VatView list={q.data as VatListing} onFile={() => file.mutate(`${tab}.xlsx`)} />
        ) : null}
      </section>
    </>
  );
}
