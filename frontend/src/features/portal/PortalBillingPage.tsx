import { useMutation, useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import { portalApi } from '../../api/endpoints';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { Spinner } from '../../components/Spinner';
import { KIND_LABEL } from '../receivables/BillingsPage';

export function PortalBillingPage() {
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: ['portal-billing', id], queryFn: () => portalApi.billing(id) });
  const pdf = useMutation({ mutationFn: () => portalApi.billingPdf(id, q.data!.documentNumber) });
  if (q.isPending) return <Spinner />;
  if (q.error) return <><Link to="/portal" className="text-sm text-brand-700">← Back</Link><ErrorAlert error={q.error} /></>;
  const b = q.data;
  return (
    <div className="space-y-4">
      <Link to="/portal" className="text-sm text-brand-700 hover:underline">← Back to account</Link>
      <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-6">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <h1 className="text-xl font-semibold">{KIND_LABEL[b.kind]} {b.documentNumber}</h1>
          <Button variant="secondary" busy={pdf.isPending} onClick={() => pdf.mutate()}>Download PDF</Button>
        </div>
        <ErrorAlert error={pdf.error} />
        <p className="text-sm text-slate-500">Dated {b.documentDate}{b.kind !== 'CREDIT_MEMO' ? ` · due ${b.dueDate}` : ''}
          {b.billingPeriod ? ` · period ${b.billingPeriod}` : ''}{b.relatedInvoiceNumber ? ` · relates to ${b.relatedInvoiceNumber}` : ''}</p>
        <p className="mt-3 text-sm">{b.description}</p>
        <table className="mt-4 min-w-full text-sm">
          <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1">Description</th><th className="py-1 text-right">Amount</th></tr></thead>
          <tbody>{b.lines.map((l) => <tr key={l.lineNo} className="border-t border-slate-100"><td className="py-1.5">{l.description}</td>
            <td className="py-1.5 text-right"><Money value={l.netAmount} /></td></tr>)}</tbody>
        </table>
        <dl className="mt-4 ml-auto grid max-w-xs grid-cols-2 gap-1 text-sm">
          <dt>Net</dt><dd className="text-right"><Money value={b.netAmount} /></dd>
          <dt>VAT</dt><dd className="text-right"><Money value={b.vatAmount} /></dd>
          <dt className="font-semibold">Total</dt><dd className="text-right font-semibold"><Money value={b.grossAmount} /></dd>
          {b.kind !== 'CREDIT_MEMO' && <><dt>Paid / credited</dt><dd className="text-right"><Money value={b.settledAmount} /></dd>
            <dt className="font-semibold">Balance</dt><dd className="text-right font-semibold"><Money value={b.openBalance} /></dd></>}
        </dl>
        <p className="mt-4 text-xs text-slate-500">This is an electronic copy for reference. It is not an official receipt or BIR-registered invoice.</p>
      </section>
    </div>
  );
}
