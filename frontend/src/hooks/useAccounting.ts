import { useQuery } from '@tanstack/react-query';
import { accountApi, periodApi } from '../api/endpoints';
import type { Account, Period } from '../api/types';

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

export function allPeriods(years: { periods: Period[] }[] | undefined): Period[] {
  return (years ?? []).flatMap((y) => y.periods);
}
