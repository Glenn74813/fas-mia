import type { Statement } from '../../api/types';
import { Money } from '../../components/Money';

const LABELS: Record<string, string> = { INVOICE: 'Invoice', CREDIT_MEMO: 'Credit memo', DEBIT_MEMO: 'Debit memo', RECEIPT: 'Payment' };

/** Statement of account lines with running balance; shared by the internal and portal views. */
export function StatementTable({ statement }: { statement: Statement }) {
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Date</th><th className="py-1 pr-3">Type</th>
          <th className="py-1 pr-3">Reference</th><th className="py-1 pr-3">Description</th><th className="py-1 text-right">Charges</th>
          <th className="py-1 text-right">Credits</th><th className="py-1 text-right">Balance</th></tr></thead>
        <tbody>
          <tr className="border-t border-slate-100 text-slate-600"><td className="py-1.5 pr-3">{statement.from}</td><td colSpan={5} className="py-1.5">Opening balance</td>
            <td className="py-1.5 text-right"><Money value={statement.openingBalance} /></td></tr>
          {statement.lines.map((l, i) => (
            <tr key={i} className="border-t border-slate-100"><td className="whitespace-nowrap py-1.5 pr-3">{l.date}</td>
              <td className="py-1.5 pr-3">{LABELS[l.type] ?? l.type}</td><td className="py-1.5 pr-3 font-mono">{l.reference}</td>
              <td className="py-1.5 pr-3">{l.description}</td><td className="py-1.5 text-right"><Money value={l.charges} blankZero /></td>
              <td className="py-1.5 text-right"><Money value={l.credits} blankZero /></td><td className="py-1.5 text-right"><Money value={l.balance} /></td></tr>
          ))}
        </tbody>
        <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td colSpan={6} className="py-1.5">Closing balance {statement.to}</td>
          <td className="py-1.5 text-right"><Money value={statement.closingBalance} /></td></tr></tfoot>
      </table>
      {statement.unappliedCollections !== '0.00' && (
        <p className="mt-2 text-xs text-slate-600">Includes unapplied payments of <Money value={statement.unappliedCollections} /> not yet matched to invoices.</p>
      )}
      <p className="mt-2 text-xs text-slate-500">A negative balance is a credit in the customer's favour.</p>
    </div>
  );
}
