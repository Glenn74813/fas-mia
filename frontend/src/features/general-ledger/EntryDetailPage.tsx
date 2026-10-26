import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import { ledgerApi } from '../../api/endpoints';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { formatDateTime } from '../../utils/format';

export function EntryDetailPage() {
  const { id = '' } = useParams();
  const entry = useQuery({ queryKey: ['entry', id], queryFn: () => ledgerApi.entry(id) });
  if (entry.isPending) return <Spinner />;
  if (entry.error) return <ErrorAlert error={entry.error} />;
  const e = entry.data;
  return (
    <>
      <PageHeader title={`Journal entry ${e.entryNumber}`} crumbs={[{ label: 'General ledger' }, { label: e.entryNumber }]} />
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <p className="mb-3 text-xs text-slate-500">Posted entries are immutable. Corrections are made with a reversal.</p>
        <dl className="grid gap-2 text-sm sm:grid-cols-4">
          <dt className="text-slate-500">Date</dt><dd>{e.accountingDate} ({e.periodName})</dd>
          <dt className="text-slate-500">Type</dt><dd>{e.voucherType}</dd>
          <dt className="text-slate-500">Prepared by</dt><dd>{e.preparedBy}</dd>
          <dt className="text-slate-500">Posted</dt><dd>{e.postedBy} · {formatDateTime(e.postedAt)}</dd>
          <dt className="text-slate-500">Voucher</dt><dd><Link className="text-brand-700 hover:underline" to={`/journal-vouchers/${e.voucherId}`}>Open voucher and documents</Link></dd>
          {e.reversalOfEntryId && <><dt className="text-slate-500">Reverses</dt>
            <dd><Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${e.reversalOfEntryId}`}>{e.reversalOfEntryNumber}</Link></dd></>}
          {e.reversedByEntryId && <><dt className="text-slate-500">Reversed by</dt>
            <dd><Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${e.reversedByEntryId}`}>{e.reversedByEntryNumber}</Link></dd></>}
        </dl>
        <p className="mt-3 text-sm">{e.description}</p>
      </section>
      <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
        <table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            <tr><th className="px-3 py-2">#</th><th className="px-3 py-2">Account</th><th className="px-3 py-2">Cost centre</th>
              <th className="px-3 py-2">Subsidiary</th><th className="px-3 py-2 text-right">Debit</th><th className="px-3 py-2 text-right">Credit</th></tr>
          </thead>
          <tbody>
            {e.lines.map((l) => (
              <tr key={l.lineNo} className="border-t border-slate-100">
                <td className="px-3 py-2">{l.lineNo}</td>
                <td className="px-3 py-2"><span className="font-mono">{l.accountCode}</span> {l.accountName}</td>
                <td className="px-3 py-2">{l.costCenterCode ?? '—'}</td>
                <td className="px-3 py-2">{l.subsidiaryCode ? `${l.subsidiaryCode} ${l.subsidiaryName}` : '—'}</td>
                <td className="px-3 py-2 text-right"><Money value={l.debit} blankZero /></td>
                <td className="px-3 py-2 text-right"><Money value={l.credit} blankZero /></td>
              </tr>
            ))}
          </tbody>
          <tfoot className="border-t-2 border-slate-300 font-medium">
            <tr><td colSpan={4} className="px-3 py-2 text-right">Totals</td>
              <td className="px-3 py-2 text-right"><Money value={e.totalDebit} /></td><td className="px-3 py-2 text-right"><Money value={e.totalCredit} /></td></tr>
          </tfoot>
        </table>
      </div>
    </>
  );
}
