import { useQuery } from '@tanstack/react-query';
import { accountApi } from '../../api/endpoints';
import type { Account, LineInput } from '../../api/types';
import { SearchSelect } from '../../components/SearchSelect';
import { postableAccounts, useChart, useCostCenters } from '../../hooks/useAccounting';
import { fromCents, isValidAmount, sumCents } from '../../utils/money';

export const emptyLine = (): LineInput => ({
  accountId: '', costCenterId: null, subsidiaryAccountId: null, description: null, debit: '', credit: '',
});

interface Props {
  lines: LineInput[];
  onChange: (lines: LineInput[]) => void;
}

/**
 * Debit/credit line grid. Totals are computed in integer centavos for display only; the server
 * re-validates every rule (balance, account status, dimensions) on submission and posting.
 */
/** Subsidiary account of the line's ledger type, searched on the server (there is one per customer and supplier). */
function SubsidiaryPicker({ n, slType, value, onChange }: { n: number; slType: string; value: string | null; onChange: (id: string | null) => void }) {
  const current = useQuery({ queryKey: ['subsidiary-account', value], queryFn: () => accountApi.subsidiaryAccount(value!), enabled: !!value });
  return (
    <SearchSelect compact id={`line-${n}-subsidiary`} label={`Line ${n} subsidiary account`} value={value ?? ''} queryKey={`subsidiary-accounts-${slType}`}
      search={(q) => accountApi.subsidiaryAccounts(slType, q)} optionLabel={(s) => `${s.code} — ${s.name}`} filter={(s) => s.status === 'ACTIVE'}
      selected={current.data} placeholder={`${slType.toLowerCase()}…`} onChange={(id) => onChange(id || null)} />
  );
}

export function LinesEditor({ lines, onChange }: Props) {
  const chart = useChart();
  const costCenters = useCostCenters();
  const accounts = postableAccounts(chart.data?.accounts);
  const byId = new Map<string, Account>(accounts.map((a) => [a.id, a]));

  const set = (i: number, patch: Partial<LineInput>) => onChange(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const debit = sumCents(lines.map((l) => l.debit));
  const credit = sumCents(lines.map((l) => l.credit));
  const difference = debit - credit;

  return (
    <div className="space-y-2">
      <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
        <table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            <tr>
              <th className="px-2 py-2">#</th><th className="px-2 py-2">Account</th><th className="px-2 py-2">Cost centre</th>
              <th className="px-2 py-2">Subsidiary</th><th className="px-2 py-2">Line description</th>
              <th className="px-2 py-2 text-right">Debit</th><th className="px-2 py-2 text-right">Credit</th><th />
            </tr>
          </thead>
          <tbody>
            {lines.map((line, i) => {
              const account = byId.get(line.accountId);
              const n = i + 1;
              return (
                <tr key={i} className="border-t border-slate-100 align-top">
                  <td className="px-2 py-2 text-slate-500">{n}</td>
                  <td className="px-2 py-2">
                    <select aria-label={`Line ${n} account`} value={line.accountId} className="w-64 rounded-md border border-slate-300 px-2 py-1"
                      onChange={(e) => set(i, { accountId: e.target.value, costCenterId: null, subsidiaryAccountId: null })}>
                      <option value="">Select account…</option>
                      {accounts.map((a) => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}
                    </select>
                  </td>
                  <td className="px-2 py-2">
                    <select aria-label={`Line ${n} cost centre`} value={line.costCenterId ?? ''}
                      className={`w-40 rounded border px-2 py-1 ${account?.requiresCostCenter && !line.costCenterId ? 'border-amber-500' : 'border-slate-300'}`}
                      onChange={(e) => set(i, { costCenterId: e.target.value || null })}>
                      <option value="">{account?.requiresCostCenter ? 'Required…' : '—'}</option>
                      {(costCenters.data ?? []).filter((c) => c.status === 'ACTIVE').map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}
                    </select>
                  </td>
                  <td className="px-2 py-2">
                    {account?.subsidiaryType
                      ? <SubsidiaryPicker n={n} slType={account.subsidiaryType} value={line.subsidiaryAccountId}
                        onChange={(id) => set(i, { subsidiaryAccountId: id })} />
                      : <select aria-label={`Line ${n} subsidiary account`} value="" disabled className="w-40 rounded-md border border-slate-300 px-2 py-1">
                        <option value="">n/a</option></select>}
                  </td>
                  <td className="px-2 py-2">
                    <input aria-label={`Line ${n} description`} value={line.description ?? ''} maxLength={500}
                      className="w-48 rounded-md border border-slate-300 px-2 py-1" onChange={(e) => set(i, { description: e.target.value || null })} />
                  </td>
                  {(['debit', 'credit'] as const).map((side) => (
                    <td key={side} className="px-2 py-2">
                      <input aria-label={`Line ${n} ${side}`} inputMode="decimal" value={line[side]} placeholder="0.00"
                        aria-invalid={!isValidAmount(line[side]) || undefined}
                        className={`w-32 rounded border px-2 py-1 text-right tabular-nums ${isValidAmount(line[side]) ? 'border-slate-300' : 'border-red-500'}`}
                        onChange={(e) => set(i, { [side]: e.target.value.replace(/[^\d.]/g, '') })} />
                    </td>
                  ))}
                  <td className="px-2 py-2">
                    <button type="button" aria-label={`Remove line ${n}`} disabled={lines.length <= 1}
                      className="text-sm text-red-700 disabled:text-slate-300" onClick={() => onChange(lines.filter((_, j) => j !== i))}>Remove</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot className="border-t-2 border-slate-300 font-medium">
            <tr>
              <td colSpan={5} className="px-2 py-2 text-right">Totals</td>
              <td className="px-2 py-2 text-right tabular-nums" data-testid="total-debit">{fromCents(debit)}</td>
              <td className="px-2 py-2 text-right tabular-nums" data-testid="total-credit">{fromCents(credit)}</td>
              <td />
            </tr>
          </tfoot>
        </table>
      </div>
      <div className="flex items-center justify-between text-sm">
        <button type="button" className="text-brand-700 hover:underline" onClick={() => onChange([...lines, emptyLine()])}>+ Add line</button>
        <span role="status" className={difference === 0n && debit > 0n ? 'text-emerald-700' : 'text-amber-700'}>
          {difference === 0n && debit > 0n ? 'Balanced' : `Out of balance by ${fromCents(difference < 0n ? -difference : difference)}`}
        </span>
      </div>
    </div>
  );
}

export function toInputLines(lines: { accountId: string; costCenterId: string | null; subsidiaryAccountId: string | null;
  description: string | null; debit: string; credit: string }[]): LineInput[] {
  return lines.map((l) => ({
    accountId: l.accountId, costCenterId: l.costCenterId, subsidiaryAccountId: l.subsidiaryAccountId,
    description: l.description, debit: l.debit === '0.00' ? '' : l.debit, credit: l.credit === '0.00' ? '' : l.credit,
  }));
}

/** Lines as sent to the API: blank amounts become "0". */
export function normaliseLines(lines: LineInput[]): LineInput[] {
  return lines.map((l) => ({ ...l, debit: l.debit.trim() || '0', credit: l.credit.trim() || '0' }));
}
