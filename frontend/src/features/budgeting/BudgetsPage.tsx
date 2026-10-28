import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { budgetApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { useFiscalYears } from '../../hooks/useAccounting';

export function BudgetsPage() {
  const { hasPermission } = useAuth();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['budgets', page], queryFn: () => budgetApi.list(page), placeholderData: keepPreviousData });
  const years = useFiscalYears();
  const [fy, setFy] = useState('');
  const create = useMutation({
    mutationFn: () => budgetApi.create(fy, `Corporate Operating Budget ${years.data?.find((y) => y.id === fy)?.fiscalYear ?? ''}`),
    onSuccess: (b) => { qc.invalidateQueries({ queryKey: ['budgets'] }); navigate(`/budgets/${b.id}`); },
  });
  const withoutBudget = (years.data ?? []).filter((y) => !(list.data?.items ?? []).some((b) => b.fiscalYearId === y.id));
  return (
    <>
      <PageHeader title="Budgets" crumbs={[{ label: 'Budget' }, { label: 'Corporate Operating Budgets' }]}
        actions={hasPermission('BUDGET_PREPARE') && withoutBudget.length > 0 && (
          <div className="flex gap-2">
            <Select aria-label="Fiscal year" value={fy} onChange={(e) => setFy(e.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
              <option value="">Fiscal year…</option>{withoutBudget.map((y) => <option key={y.id} value={y.id}>{y.fiscalYear}</option>)}</Select>
            <Button disabled={!fy} busy={create.isPending} onClick={() => create.mutate()}>Create budget</Button>
          </div>
        )} />
      <ErrorAlert error={list.error ?? create.error} />
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Budget</th><th className="px-3 py-2">Year</th>
              <th className="px-3 py-2">Status</th><th className="px-3 py-2 text-right">Allotment</th><th className="px-3 py-2 text-right">Reserved</th>
              <th className="px-3 py-2 text-right">Actual</th><th className="px-3 py-2 text-right">Available</th></tr></thead>
            <tbody>{(list.data?.items ?? []).map((b) => (
              <tr key={b.id} className="border-t border-slate-100"><td className="px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/budgets/${b.id}`}>{b.name}</Link></td>
                <td className="px-3 py-2">{b.fiscalYear}</td><td className="px-3 py-2"><StatusBadge status={b.status} /></td>
                <td className="px-3 py-2 text-right"><Money value={b.totalAllotment} /></td><td className="px-3 py-2 text-right"><Money value={b.totalReserved} /></td>
                <td className="px-3 py-2 text-right"><Money value={b.totalActual} /></td><td className="px-3 py-2 text-right"><Money value={b.totalAvailable} /></td></tr>))}
              {list.data?.items.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No budgets yet.</td></tr>}</tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
