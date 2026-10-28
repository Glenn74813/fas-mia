import { useQuery } from '@tanstack/react-query';
import { accountApi, periodApi } from '../api/endpoints';
import type { Account, AccountPurpose, Period } from '../api/types';

export function useChart(enabled = true) {
  return useQuery({ queryKey: ['chart'], queryFn: accountApi.chart, staleTime: 300_000, enabled });
}

export function useCostCenters() {
  return useQuery({ queryKey: ['cost-centers'], queryFn: accountApi.costCenters, staleTime: 300_000 });
}

export function useFiscalYears() {
  return useQuery({ queryKey: ['fiscal-years'], queryFn: periodApi.fiscalYears });
}

export function postableAccounts(accounts: Account[] | undefined): Account[] {
  return (accounts ?? []).filter((a) => a.postable && a.status === 'ACTIVE');
}

/** Active postable accounts fit for a purpose; the server refuses any other account for that field. */
export function accountsFor(accounts: Account[] | undefined, purpose: AccountPurpose): Account[] {
  return postableAccounts(accounts).filter((a) => a.purposes?.includes(purpose));
}

/** Active cost centres, for forms that create or change records (filters still list them all). */
export function activeCostCenters<T extends { status: string }>(list: T[] | undefined): T[] {
  return (list ?? []).filter((c) => c.status === 'ACTIVE');
}

export function allPeriods(years: { periods: Period[] }[] | undefined): Period[] {
  return (years ?? []).flatMap((y) => y.periods);
}
