import { TriangleAlert } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { useChart } from '../hooks/useAccounting';

/** Persistent warning while the non-approved placeholder chart of accounts is in use. */
export function PlaceholderBanner() {
  const { hasPermission } = useAuth();
  const canSee = hasPermission('LEDGER_VIEW') || hasPermission('ACCOUNT_ADMINISTER');
  const chart = useChart(canSee);
  if (!canSee || !chart.data || chart.data.activePlaceholderAccounts === 0) return null;
  return (
    <div role="note" className="flex items-center gap-2.5 border-b border-amber-200 bg-amber-50 px-6 py-2 text-[13px] text-amber-900 lg:px-8">
      <TriangleAlert aria-hidden className="h-4 w-4 shrink-0 text-amber-600" strokeWidth={2} />
      <span><strong className="font-semibold">Placeholder chart of accounts in use.</strong> {chart.data.activePlaceholderAccounts} active accounts are
      development placeholders, not the AVTECH-approved chart. Balances shown are not official.</span>
    </div>
  );
}
