import { Link } from 'react-router';
import type { VoucherSummary } from '../../api/types';
import { Money } from '../../components/Money';
import { StatusBadge } from '../../components/StatusBadge';

export function VoucherTable({ vouchers, empty }: { vouchers: VoucherSummary[]; empty: string }) {
  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
      <table className="min-w-full text-left text-sm">
        <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
          <tr><th className="px-3 py-2">Number</th><th className="px-3 py-2">Date</th><th className="px-3 py-2">Type</th>
            <th className="px-3 py-2">Description</th><th className="px-3 py-2">Prepared by</th><th className="px-3 py-2">Status</th>
            <th className="px-3 py-2 text-right">Amount</th></tr>
        </thead>
        <tbody>
          {vouchers.map((v) => (
            <tr key={v.id} className="border-t border-slate-100">
              <td className="whitespace-nowrap px-3 py-2"><Link className="font-mono text-brand-700 hover:underline" to={`/journal-vouchers/${v.id}`}>
                {v.voucherNumber ?? `Draft ${v.id.slice(0, 8)}`}</Link></td>
              <td className="whitespace-nowrap px-3 py-2">{v.accountingDate}</td>
              <td className="px-3 py-2 text-xs">{v.voucherType}</td>
              <td className="max-w-md truncate px-3 py-2" title={v.description}>{v.description}</td>
              <td className="px-3 py-2">{v.preparedBy}</td>
              <td className="space-x-1 px-3 py-2"><StatusBadge status={v.status} />
                {v.currentStep && <span className="text-xs text-slate-500">awaiting {v.currentStep.toLowerCase()}</span>}</td>
              <td className="px-3 py-2 text-right"><Money value={v.totalDebit} /></td>
            </tr>
          ))}
          {vouchers.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">{empty}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
