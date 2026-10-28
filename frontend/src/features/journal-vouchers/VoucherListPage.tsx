import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { voucherApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { VoucherTable } from './VoucherTable';

const STATUSES = ['', 'DRAFT', 'RETURNED', 'SUBMITTED', 'APPROVED', 'POSTED', 'REJECTED', 'CANCELLED'];
const DATE_INPUT = 'rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm';

export function VoucherListPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [mine, setMine] = useState(false);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState('');
  const [notice, setNotice] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);
  const onSort = (next: string) => { setSort(next); setPage(0); };
  const rangeError = from && to && to < from ? 'The To date cannot be earlier than the From date.' : '';
  const list = useQuery({
    queryKey: ['vouchers', status, q, mine, from, to, page, sort],
    queryFn: () => voucherApi.list({ status, q, mine, from: from || undefined, to: to || undefined, page, size: 25, sort: sort || undefined }),
    placeholderData: keepPreviousData,
    enabled: !rangeError,
  });
  const exportFile = useMutation({
    mutationFn: () => voucherApi.exportExcel({ status: status || undefined, q: q || undefined, mine, from, to }),
  });
  const importFile = useMutation({
    mutationFn: (file: File) => voucherApi.importExcel(file),
    onSuccess: (r) => {
      setNotice(`Imported ${r.vouchers} draft voucher${r.vouchers === 1 ? '' : 's'} (${r.lines} lines). Review and submit them as usual.`);
      setStatus('DRAFT');
      setMine(true);
      setPage(0);
      void qc.invalidateQueries({ queryKey: ['vouchers'] });
    },
  });
  const template = useMutation({ mutationFn: () => voucherApi.importTemplate() });

  return (
    <>
      <PageHeader title="Journal entry vouchers" crumbs={[{ label: 'Accounting' }, { label: 'Journal vouchers' }]}
        actions={hasPermission('JOURNAL_CREATE') && (
          <Link to="/journal-vouchers/new" className="rounded-md bg-brand-700 px-4 py-2 text-sm font-medium text-white hover:bg-brand-900">New voucher</Link>
        )} />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <input aria-label="Search vouchers" placeholder="Search number or description" value={q}
          onChange={(e) => { setQ(e.target.value); setPage(0); }} className="w-72 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
        <Select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}
          className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          {STATUSES.map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
        </Select>
        <label className="flex items-center gap-2 text-sm">From
          <input type="date" aria-label="From date" value={from} max={to || undefined}
            onChange={(e) => { setFrom(e.target.value); setPage(0); }} className={DATE_INPUT} />
        </label>
        <label className="flex items-center gap-2 text-sm">To
          <input type="date" aria-label="To date" value={to} min={from || undefined}
            onChange={(e) => { setTo(e.target.value); setPage(0); }} className={DATE_INPUT} />
        </label>
        {(from || to) && (
          <button type="button" onClick={() => { setFrom(''); setTo(''); setPage(0); }} className="py-2 text-sm text-brand-700 hover:underline">Clear dates</button>
        )}
        {hasPermission('JOURNAL_CREATE') && (
          <label className="flex items-center gap-2 py-2 text-sm">
            <input type="checkbox" checked={mine} onChange={(e) => { setMine(e.target.checked); setPage(0); }} /> Prepared by me
          </label>
        )}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <Button variant="secondary" busy={exportFile.isPending} disabled={!from || !to || !!rangeError}
            title={!from || !to ? 'Choose the From and To dates to export' : 'Download the vouchers in this date range as Excel'}
            onClick={() => exportFile.mutate()}>Export Excel</Button>
          {hasPermission('JOURNAL_CREATE') && (
            <>
              <Button variant="secondary" busy={importFile.isPending}
                onClick={() => { setNotice(''); importFile.reset(); fileRef.current?.click(); }}>Import Excel</Button>
              <input ref={fileRef} type="file" hidden aria-label="Excel file to import"
                accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) importFile.mutate(f); }} />
              <button type="button" onClick={() => template.mutate()} className="text-sm text-brand-700 hover:underline">Template</button>
            </>
          )}
        </div>
      </div>
      {(!from || !to) && <p className="-mt-2 mb-3 text-xs text-slate-500">Choose From and To dates to limit the list and to export that span to Excel.</p>}
      {rangeError && <p role="alert" className="mb-3 text-sm text-red-700">{rangeError}</p>}
      {notice && <p role="status" className="mb-3 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-800">{notice}</p>}
      <ErrorAlert error={importFile.error ?? exportFile.error ?? template.error} />
      <ErrorAlert error={list.error} />
      {list.isPending && !rangeError ? <Spinner /> : list.data && <VoucherTable vouchers={list.data.items} empty="No vouchers match." sort={sort} onSort={onSort} />}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
