import { ACCOUNTING_HELP } from './accounting';
import { ADMINISTRATION_HELP } from './administration';
import { ASSETS_TREASURY_HELP } from './assetsTreasury';
import { PAYABLES_RECEIVABLES_HELP } from './payablesReceivables';
import type { ModuleHelp } from './types';

export type { HelpConnection, ModuleHelp } from './types';

/** Help for every module, keyed by the module's route. */
export const MODULE_HELP: Record<string, ModuleHelp> = {
  ...ACCOUNTING_HELP,
  ...PAYABLES_RECEIVABLES_HELP,
  ...ASSETS_TREASURY_HELP,
  ...ADMINISTRATION_HELP,
};

/** Modules that exist only as detail pages (a posted entry, a reconciliation), so there is no page to link to. */
export const DETAIL_ONLY = new Set(['/general-ledger/entries', '/treasury/reconciliations']);

/**
 * The help for the page at this path: the module whose route is the longest prefix of it, so detail and form pages
 * (/journal-vouchers/123/edit) share their module's help. The dashboard ('/') matches only itself.
 */
export function helpFor(pathname: string): { key: string; help: ModuleHelp } | null {
  const path = pathname.replace(/\/+$/, '') || '/';
  if (path === '/') return MODULE_HELP['/'] ? { key: '/', help: MODULE_HELP['/'] } : null;
  let best: string | null = null;
  for (const key of Object.keys(MODULE_HELP)) {
    if (key !== '/' && (path === key || path.startsWith(key + '/')) && (!best || key.length > best.length)) best = key;
  }
  return best ? { key: best, help: MODULE_HELP[best]! } : null;
}
