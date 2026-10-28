import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router';
import { voucherApi } from '../../api/endpoints';
import type { LineInput, VoucherDetail, VoucherInput } from '../../api/types';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { isValidAmount } from '../../utils/money';
import { emptyLine, LinesEditor, normaliseLines, toInputLines } from './LinesEditor';

/** Create a voucher, or edit a DRAFT/RETURNED one (route /journal-vouchers/:id/edit). */
export function VoucherFormPage() {
  const { id } = useParams();
  const existing = useQuery({ queryKey: ['voucher', id], queryFn: () => voucherApi.get(id!), enabled: !!id });
  if (id && existing.isPending) return <Spinner />;
  if (id && existing.error) return <ErrorAlert error={existing.error} />;
  return <VoucherForm existing={existing.data} />;
}

function VoucherForm({ existing }: { existing?: VoucherDetail }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [form, setForm] = useState<Omit<VoucherInput, 'lines'>>({
    voucherType: (existing?.voucherType as VoucherInput['voucherType']) ?? 'STANDARD',
    accountingDate: existing?.accountingDate ?? new Date().toISOString().slice(0, 10),
    adjustmentPeriod: existing?.periodName.startsWith('Adjustments') ?? false,
    description: existing?.description ?? '',
    supportingReference: existing?.supportingReference ?? null,
  });
  const [lines, setLines] = useState<LineInput[]>(existing ? toInputLines(existing.lines) : [emptyLine(), emptyLine()]);

  const save = useMutation({
    mutationFn: () => {
      const body = { ...form, lines: normaliseLines(lines) };
      return existing ? voucherApi.update(existing.id, existing.version, body) : voucherApi.create(body);
    },
    onSuccess: (v) => {
      qc.setQueryData(['voucher', v.id], v);
      qc.invalidateQueries({ queryKey: ['vouchers'] });
      navigate(`/journal-vouchers/${v.id}`);
    },
  });

  const valid = form.description.trim() !== '' && lines.every((l) => l.accountId && isValidAmount(l.debit) && isValidAmount(l.credit));
  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate();
  };

  return (
    <>
      <PageHeader title={existing ? `Edit voucher` : 'New journal entry voucher'}
        crumbs={[{ label: 'Accounting' }, { label: 'Journal vouchers', to: '/journal-vouchers' }, { label: existing ? 'Edit' : 'New' }]} />
      <form onSubmit={submit} className="space-y-4">
        <ErrorAlert error={save.error} />
        <div className="grid gap-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:grid-cols-4">
          <div className="space-y-1.5">
            <label htmlFor="voucherType" className="block text-sm font-medium text-slate-700">Voucher type</label>
            <Select id="voucherType" value={form.voucherType} className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm"
              onChange={(e) => setForm({ ...form, voucherType: e.target.value as VoucherInput['voucherType'] })}>
              <option value="STANDARD">Standard</option>
              <option value="ADJUSTING">Adjusting</option>
              <option value="OPENING">Opening balances</option>
            </Select>
          </div>
          <Field label="Accounting date" type="date" required value={form.accountingDate}
            onChange={(e) => setForm({ ...form, accountingDate: e.target.value })} />
          <Field label="Supporting reference" value={form.supportingReference ?? ''} maxLength={200}
            hint="e.g. invoice or memo number" onChange={(e) => setForm({ ...form, supportingReference: e.target.value || null })} />
          {form.voucherType === 'ADJUSTING' && (
            <label className="flex items-center gap-2 self-end pb-2 text-sm">
              <input type="checkbox" checked={form.adjustmentPeriod}
                onChange={(e) => setForm({ ...form, adjustmentPeriod: e.target.checked })} />
              Post to year-end adjustment period (date must be 31 Dec)
            </label>
          )}
          <div className="md:col-span-4">
            <Field label="Description / particulars" required value={form.description} maxLength={1000}
              onChange={(e) => setForm({ ...form, description: e.target.value })} />
          </div>
        </div>
        <LinesEditor lines={lines} onChange={setLines} />
        <div className="flex gap-2">
          <Button type="submit" busy={save.isPending} disabled={!valid}>Save draft</Button>
          <Button variant="secondary" onClick={() => navigate(-1)}>Cancel</Button>
        </div>
        <p className="text-xs text-slate-500">Drafts may be incomplete. Balance, accounts, dimensions, period and supporting
          documents are checked when you submit.</p>
      </form>
    </>
  );
}
