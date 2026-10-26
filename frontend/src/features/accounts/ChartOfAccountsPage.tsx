import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { accountApi } from '../../api/endpoints';
import type { Account } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { useChart } from '../../hooks/useAccounting';

function depth(a: Account, byId: Map<string, Account>): number {
  let d = 0;
  let p = a.parentId;
  while (p && d < 20) {
    d++;
    p = byId.get(p)?.parentId ?? null;
  }
  return d;
}

export function ChartOfAccountsPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const chart = useChart();
  const [q, setQ] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);
  const importCsv = useMutation({
    mutationFn: (file: File) => accountApi.importCsv(file, false),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['chart'] }),
  });
  if (chart.isPending) return <Spinner />;
  if (chart.error) return <ErrorAlert error={chart.error} />;
  const byId = new Map(chart.data.accounts.map((a) => [a.id, a]));
  const needle = q.trim().toLowerCase();
  const rows = chart.data.accounts.filter((a) => !needle || a.code.toLowerCase().includes(needle) || a.name.toLowerCase().includes(needle));

  return (
    <>
      <PageHeader title="Chart of accounts" crumbs={[{ label: 'General ledger' }, { label: 'Chart of accounts' }]}
        actions={hasPermission('ACCOUNT_ADMINISTER') && (
          <>
            <input ref={fileRef} type="file" accept=".csv" className="hidden" aria-label="Choose chart of accounts CSV"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) importCsv.mutate(f); e.target.value = ''; }} />
            <Button variant="secondary" busy={importCsv.isPending} onClick={() => fileRef.current?.click()}>Import approved chart (CSV)</Button>
          </>
        )} />
      {chart.data.activePlaceholderAccounts > 0 && (
        <p className="mb-4 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm">
          The accounts marked <Badge tone="amber">PLACEHOLDER</Badge> are a generic development structure, not MIAA's approved
          chart. Import the approved chart (all-or-nothing CSV) before user acceptance testing; production configuration rejects
          postings to placeholder accounts.
        </p>
      )}
      <ErrorAlert error={importCsv.error} />
      {importCsv.data && <p role="status" className="mb-3 text-sm text-emerald-700">{importCsv.data.created} accounts imported.</p>}
      <input aria-label="Filter accounts" placeholder="Filter by code or name" value={q} onChange={(e) => setQ(e.target.value)}
        className="mb-3 w-72 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
      <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
        <table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            <tr><th className="px-3 py-2">Code</th><th className="px-3 py-2">Name</th><th className="px-3 py-2">Type</th><th className="px-3 py-2">Normal</th>
              <th className="px-3 py-2">Dimensions</th><th className="px-3 py-2">FS line</th><th className="px-3 py-2">Status</th></tr>
          </thead>
          <tbody>
            {rows.map((a) => (
              <tr key={a.id} className={`border-t border-slate-100 ${a.postable ? '' : 'bg-slate-50 font-medium'}`}>
                <td className="px-3 py-1.5 font-mono">{a.code}</td>
                <td className="px-3 py-1.5" style={{ paddingLeft: `${0.75 + depth(a, byId) * 1.25}rem` }}>{a.name}</td>
                <td className="px-3 py-1.5 text-xs">{a.accountType}</td>
                <td className="px-3 py-1.5 text-xs">{a.normalBalance}</td>
                <td className="space-x-1 px-3 py-1.5">
                  {!a.postable && <Badge>HEADER</Badge>}
                  {a.requiresCostCenter && <Badge tone="blue">COST CENTRE</Badge>}
                  {a.subsidiaryType && <Badge tone="blue">{`SL: ${a.subsidiaryType}`}</Badge>}
                  {a.placeholder && <Badge tone="amber">PLACEHOLDER</Badge>}
                </td>
                <td className="px-3 py-1.5 text-xs">{a.fsLineCode ?? '—'}</td>
                <td className="px-3 py-1.5"><StatusBadge status={a.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
