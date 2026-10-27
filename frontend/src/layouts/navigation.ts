export interface NavItem {
  label: string;
  to: string;
  /** Shown when the user holds any of these permissions; empty means every signed-in internal user. */
  anyOf: string[];
  section?: string;
}

// Only implemented modules are listed. Further modules are added as each phase is delivered.
export const NAV_ITEMS: NavItem[] = [
  { label: 'Dashboard', to: '/', anyOf: [] },
  { label: 'Inbox', to: '/inbox', anyOf: [] },
  { label: 'Journal vouchers', to: '/journal-vouchers', anyOf: ['LEDGER_VIEW', 'JOURNAL_CREATE'], section: 'Accounting' },
  { label: 'Recurring journals', to: '/recurring-journals', anyOf: ['RECURRING_JOURNAL_MANAGE'], section: 'Accounting' },
  { label: 'Suppliers', to: '/payables/suppliers', anyOf: ['AP_VIEW'], section: 'Payables' },
  { label: 'Supplier invoices', to: '/payables/invoices', anyOf: ['AP_VIEW'], section: 'Payables' },
  { label: 'Disbursement vouchers', to: '/payables/disbursements', anyOf: ['AP_VIEW'], section: 'Payables' },
  { label: 'Payables reports', to: '/payables/reports', anyOf: ['AP_VIEW'], section: 'Payables' },
  { label: 'Customers', to: '/receivables/customers', anyOf: ['AR_VIEW'], section: 'Receivables' },
  { label: 'Billing', to: '/receivables/billings', anyOf: ['AR_VIEW'], section: 'Receivables' },
  { label: 'Collections', to: '/receivables/receipts', anyOf: ['AR_VIEW', 'COLLECTION_RECORD'], section: 'Receivables' },
  { label: 'Inquiries', to: '/receivables/inquiries', anyOf: ['AR_VIEW'], section: 'Receivables' },
  { label: 'Portal access', to: '/receivables/portal-access', anyOf: ['PORTAL_ACCESS_APPROVE', 'CUSTOMER_MANAGE'], section: 'Receivables' },
  { label: 'Receivables reports', to: '/receivables/reports', anyOf: ['AR_VIEW'], section: 'Receivables' },
  { label: 'Deposits', to: '/treasury/deposits', anyOf: ['TREASURY_VIEW', 'DEPOSIT_PREPARE'], section: 'Treasury' },
  { label: 'Daily collections', to: '/treasury/daily-collections', anyOf: ['TREASURY_VIEW', 'COLLECTION_RECORD'], section: 'Treasury' },
  { label: 'Bank statements', to: '/treasury/statements', anyOf: ['TREASURY_VIEW'], section: 'Treasury' },
  { label: 'Payment files', to: '/treasury/payment-files', anyOf: ['PAYMENT_RELEASE', 'TREASURY_VIEW'], section: 'Treasury' },
  { label: 'Cash position', to: '/treasury/cash-position', anyOf: ['TREASURY_VIEW'], section: 'Treasury' },
  { label: 'Cash-flow forecast', to: '/treasury/cash-forecast', anyOf: ['TREASURY_VIEW'], section: 'Treasury' },
  { label: 'Asset register', to: '/fixed-assets', anyOf: ['FA_VIEW'], section: 'Fixed assets' },
  { label: 'Depreciation', to: '/fixed-assets/depreciation', anyOf: ['FA_VIEW'], section: 'Fixed assets' },
  { label: 'Asset setup', to: '/fixed-assets/setup', anyOf: ['FA_VIEW'], section: 'Fixed assets' },
  { label: 'PPE reports', to: '/fixed-assets/reports', anyOf: ['FA_VIEW'], section: 'Fixed assets' },
  { label: 'Stock on hand', to: '/inventory/items', anyOf: ['INVENTORY_VIEW'], section: 'Inventory' },
  { label: 'Stock documents', to: '/inventory/documents', anyOf: ['INVENTORY_VIEW'], section: 'Inventory' },
  { label: 'Inventory reports', to: '/inventory/reports', anyOf: ['INVENTORY_VIEW', 'AP_VIEW'], section: 'Inventory' },
  { label: 'Budgets', to: '/budgets', anyOf: ['BUDGET_VIEW', 'BUDGET_PREPARE', 'BUDGET_APPROVE'], section: 'Budget' },
  { label: 'Financial statements', to: '/reports/financial-statements', anyOf: ['FINANCIAL_REPORT_VIEW'], section: 'Reports' },
  { label: 'My reports', to: '/reports/jobs', anyOf: ['FINANCIAL_REPORT_VIEW', 'FINANCIAL_REPORT_EXPORT', 'BUDGET_VIEW', 'AP_VIEW', 'AR_VIEW',
    'FA_VIEW', 'INVENTORY_VIEW', 'AUDIT_LOG_VIEW', 'SECURITY_EVENT_VIEW'], section: 'Reports' },
  { label: 'Trial balance', to: '/general-ledger/trial-balance', anyOf: ['LEDGER_VIEW'], section: 'General ledger' },
  { label: 'Account ledger', to: '/general-ledger/account-ledger', anyOf: ['LEDGER_VIEW'], section: 'General ledger' },
  { label: 'Chart of accounts', to: '/chart-of-accounts', anyOf: ['LEDGER_VIEW', 'ACCOUNT_ADMINISTER'], section: 'General ledger' },
  { label: 'Open items', to: '/general-ledger/open-items', anyOf: ['LEDGER_VIEW'], section: 'General ledger' },
  { label: 'Fiscal periods', to: '/fiscal-periods', anyOf: ['LEDGER_VIEW'], section: 'General ledger' },
  { label: 'Close calendar', to: '/close', anyOf: ['LEDGER_VIEW'], section: 'General ledger' },
  { label: 'Finance settings', to: '/finance-settings', anyOf: ['LEDGER_VIEW', 'ACCOUNT_ADMINISTER', 'BANK_ACCOUNT_MANAGE'], section: 'General ledger' },
  { label: 'Opening balances', to: '/migration', anyOf: ['MIGRATION_PREPARE', 'MIGRATION_APPROVE'], section: 'Migration' },
  { label: 'Parallel run', to: '/migration/parallel-runs', anyOf: ['MIGRATION_PREPARE', 'MIGRATION_APPROVE'], section: 'Migration' },
  { label: 'Tickets', to: '/tickets', anyOf: ['TICKET_CREATE', 'TICKET_VIEW_ALL', 'TICKET_WORK'], section: 'Support' },
  { label: 'Service levels', to: '/tickets/sla', anyOf: ['TICKET_ADMIN', 'TICKET_WORK', 'TICKET_VIEW_ALL'], section: 'Support' },
  { label: 'Users', to: '/admin/users', anyOf: ['USER_VIEW'], section: 'Administration' },
  { label: 'Roles & permissions', to: '/admin/roles', anyOf: ['ROLE_VIEW'], section: 'Administration' },
  { label: 'Organisation', to: '/admin/organization', anyOf: ['USER_VIEW', 'ORGANIZATION_ADMINISTER'], section: 'Administration' },
  { label: 'Access grants', to: '/admin/access-grants', anyOf: ['ACCESS_GRANT_APPROVE', 'USER_ADMINISTER'], section: 'Administration' },
  { label: 'Access reviews', to: '/admin/access-reviews', anyOf: ['ACCESS_REVIEW_MANAGE', 'ACCESS_REVIEW_PERFORM', 'USER_ADMINISTER'], section: 'Administration' },
  { label: 'Approval limits', to: '/admin/approval-limits', anyOf: ['APPROVAL_LIMIT_MANAGE', 'AUDIT_LOG_VIEW', 'ROLE_VIEW'], section: 'Administration' },
  { label: 'Data export', to: '/admin/data-exports', anyOf: ['DATA_EXPORT'], section: 'Administration' },
  { label: 'Records retention', to: '/admin/records-retention', anyOf: ['RECORDS_RETENTION_MANAGE', 'DATA_EXPORT', 'AUDIT_LOG_VIEW'], section: 'Administration' },
  { label: 'Integrations', to: '/integrations', anyOf: ['INTEGRATION_VIEW'], section: 'Administration' },
  { label: 'Audit trail', to: '/audit', anyOf: ['AUDIT_LOG_VIEW', 'SECURITY_EVENT_VIEW'], section: 'Administration' },
  { label: 'Anomalies', to: '/audit/anomalies', anyOf: ['ANOMALY_VIEW'], section: 'Administration' },
];

export function visibleNav(hasPermission: (p: string) => boolean): NavItem[] {
  return NAV_ITEMS.filter((i) => i.anyOf.length === 0 || i.anyOf.some(hasPermission));
}
