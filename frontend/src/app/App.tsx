import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, Navigate, RouterProvider, type RouteObject } from 'react-router';
import { ApiError } from '../api/client';
import { AuthProvider } from '../auth/AuthProvider';
import { RequireAuth } from '../auth/RequireAuth';
import { CreateUserPage } from '../features/admin/CreateUserPage';
import { RoleDetailPage } from '../features/admin/RoleDetailPage';
import { RolesPage } from '../features/admin/RolesPage';
import { UserDetailPage } from '../features/admin/UserDetailPage';
import { UsersPage } from '../features/admin/UsersPage';
import { ChartOfAccountsPage } from '../features/accounts/ChartOfAccountsPage';
import { AnomaliesPage } from '../features/audit/AnomaliesPage';
import { AuditPage } from '../features/audit/AuditPage';
import { AccountLedgerPage } from '../features/general-ledger/AccountLedgerPage';
import { EntryDetailPage } from '../features/general-ledger/EntryDetailPage';
import { TrialBalancePage } from '../features/general-ledger/TrialBalancePage';
import { RecurringJournalsPage } from '../features/journal-vouchers/RecurringJournalsPage';
import { VoucherDetailPage } from '../features/journal-vouchers/VoucherDetailPage';
import { VoucherFormPage } from '../features/journal-vouchers/VoucherFormPage';
import { VoucherListPage } from '../features/journal-vouchers/VoucherListPage';
import { InboxPage } from '../features/approvals/InboxPage';
import { AccessGrantsPage } from '../features/admin/AccessGrantsPage';
import { AccessReviewCyclePage, AccessReviewsPage } from '../features/admin/AccessReviewsPage';
import { ApprovalLimitsPage } from '../features/admin/ApprovalLimitsPage';
import { OrganizationPage } from '../features/admin/OrganizationPage';
import { CloseCalendarPage } from '../features/periods/CloseCalendarPage';
import { FiscalPeriodsPage } from '../features/periods/FiscalPeriodsPage';
import { OpenItemsPage } from '../features/general-ledger/OpenItemsPage';
import { BudgetDetailPage } from '../features/budgeting/BudgetDetailPage';
import { BudgetsPage } from '../features/budgeting/BudgetsPage';
import { DisbursementDetailPage } from '../features/payables/DisbursementDetailPage';
import { DisbursementVouchersPage, NewDisbursementPage } from '../features/payables/DisbursementVouchersPage';
import { InvoiceDetailPage } from '../features/payables/InvoiceDetailPage';
import { InvoiceFormPage } from '../features/payables/InvoiceFormPage';
import { InvoicesPage } from '../features/payables/InvoicesPage';
import { PayablesReportsPage } from '../features/payables/PayablesReportsPage';
import { SuppliersPage } from '../features/payables/SuppliersPage';
import { BillingDetailPage } from '../features/receivables/BillingDetailPage';
import { BillingFormPage } from '../features/receivables/BillingFormPage';
import { BillingsPage } from '../features/receivables/BillingsPage';
import { CustomerDetailPage } from '../features/receivables/CustomerDetailPage';
import { CustomersPage } from '../features/receivables/CustomersPage';
import { ReceiptDetailPage } from '../features/receivables/ReceiptDetailPage';
import { NewReceiptPage, ReceiptsPage } from '../features/receivables/ReceiptsPage';
import { ReceivablesReportsPage } from '../features/receivables/ReceivablesReportsPage';
import { PortalBillingPage } from '../features/portal/PortalBillingPage';
import { BankStatementsPage } from '../features/treasury/BankStatementsPage';
import { CashPositionPage } from '../features/treasury/CashPositionPage';
import { CollectionReportPage } from '../features/treasury/CollectionReportPage';
import { DepositDetailPage } from '../features/treasury/DepositDetailPage';
import { DepositsPage, NewDepositPage } from '../features/treasury/DepositsPage';
import { ReconciliationPage } from '../features/treasury/ReconciliationPage';
import { AssetDetailPage } from '../features/fixed-assets/AssetDetailPage';
import { AssetFormPage } from '../features/fixed-assets/AssetFormPage';
import { AssetRegisterPage } from '../features/fixed-assets/AssetRegisterPage';
import { AssetSetupPage } from '../features/fixed-assets/AssetSetupPage';
import { DepreciationRunPage, DepreciationRunsPage } from '../features/fixed-assets/DepreciationPage';
import { FaReportsPage } from '../features/fixed-assets/FaReportsPage';
import { InventoryDocumentPage, InventoryDocumentsPage, NewInventoryDocumentPage } from '../features/inventory/InventoryDocumentsPage';
import { InventoryItemsPage, ItemMovementsPage } from '../features/inventory/InventoryItemsPage';
import { InventoryReportsPage } from '../features/inventory/InventoryReportsPage';
import { FinancialStatementsPage } from '../features/reporting/FinancialStatementsPage';
import { ReportJobsPage } from '../features/reporting/ReportJobsPage';
import { IntegrationsPage } from '../features/integrations/IntegrationsPage';
import { SupplierBankPage } from '../features/payables/SupplierBankPage';
import { PaymentFilesPage } from '../features/treasury/PaymentFilesPage';
import { SlaSettingsPage } from '../features/tickets/SlaSettingsPage';
import { TicketDetailPage } from '../features/tickets/TicketDetailPage';
import { TicketsPage } from '../features/tickets/TicketsPage';
import { MigrationBatchPage, MigrationBatchesPage } from '../features/migration/MigrationPages';
import { ParallelRunPage, ParallelRunsPage } from '../features/migration/ParallelRunPages';
import { PortalAccessPage } from '../features/receivables/PortalAccessRequests';
import { FinanceSettingsPage } from '../features/settings/FinanceSettingsPage';
import { ChangePasswordPage } from '../features/auth/ChangePasswordPage';
import { ForgotPasswordPage } from '../features/auth/ForgotPasswordPage';
import { LoginPage } from '../features/auth/LoginPage';
import { MfaPage } from '../features/auth/MfaPage';
import { ResetPasswordPage } from '../features/auth/ResetPasswordPage';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { ProfilePage } from '../features/profile/ProfilePage';
import { PortalHomePage } from '../features/portal/PortalHomePage';
import { InquiriesPage } from '../features/receivables/InquiriesPage';
import { InternalLayout } from '../layouts/InternalLayout';
import { PortalLayout } from '../layouts/PortalLayout';

export const routes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  { path: '/login/mfa', element: <MfaPage /> },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },
  { path: '/change-password', element: <ChangePasswordPage /> },
  {
    path: '/',
    element: <RequireAuth><InternalLayout /></RequireAuth>,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'profile', element: <ProfilePage /> },
      { path: 'admin/users', element: <RequireAuth anyOf={['USER_VIEW']}><UsersPage /></RequireAuth> },
      { path: 'admin/users/new', element: <RequireAuth anyOf={['USER_ADMINISTER']}><CreateUserPage /></RequireAuth> },
      { path: 'admin/users/:id', element: <RequireAuth anyOf={['USER_VIEW']}><UserDetailPage /></RequireAuth> },
      { path: 'admin/organization', element: <RequireAuth anyOf={['USER_VIEW', 'ORGANIZATION_ADMINISTER']}><OrganizationPage /></RequireAuth> },
      { path: 'admin/access-grants', element: <RequireAuth anyOf={['ACCESS_GRANT_APPROVE', 'USER_ADMINISTER', 'AUDIT_LOG_VIEW']}><AccessGrantsPage /></RequireAuth> },
      { path: 'admin/access-reviews', element: <RequireAuth anyOf={['ACCESS_REVIEW_MANAGE', 'ACCESS_REVIEW_PERFORM', 'USER_ADMINISTER']}><AccessReviewsPage /></RequireAuth> },
      { path: 'admin/access-reviews/:id', element: <RequireAuth anyOf={['ACCESS_REVIEW_MANAGE', 'ACCESS_REVIEW_PERFORM', 'USER_ADMINISTER']}><AccessReviewCyclePage /></RequireAuth> },
      { path: 'admin/approval-limits', element: <RequireAuth anyOf={['APPROVAL_LIMIT_MANAGE', 'AUDIT_LOG_VIEW', 'ROLE_VIEW']}><ApprovalLimitsPage /></RequireAuth> },
      { path: 'admin/roles', element: <RequireAuth anyOf={['ROLE_VIEW']}><RolesPage /></RequireAuth> },
      { path: 'admin/roles/:code', element: <RequireAuth anyOf={['ROLE_VIEW']}><RoleDetailPage /></RequireAuth> },
      { path: 'journal-vouchers', element: <RequireAuth anyOf={['LEDGER_VIEW', 'JOURNAL_CREATE']}><VoucherListPage /></RequireAuth> },
      { path: 'journal-vouchers/new', element: <RequireAuth anyOf={['JOURNAL_CREATE']}><VoucherFormPage /></RequireAuth> },
      { path: 'journal-vouchers/:id', element: <RequireAuth anyOf={['LEDGER_VIEW']}><VoucherDetailPage /></RequireAuth> },
      { path: 'journal-vouchers/:id/edit', element: <RequireAuth anyOf={['JOURNAL_CREATE']}><VoucherFormPage /></RequireAuth> },
      { path: 'inbox', element: <InboxPage /> },
      { path: 'approvals', element: <Navigate to="/inbox" replace /> },
      { path: 'recurring-journals', element: <RequireAuth anyOf={['RECURRING_JOURNAL_MANAGE']}><RecurringJournalsPage /></RequireAuth> },
      { path: 'general-ledger/trial-balance', element: <RequireAuth anyOf={['LEDGER_VIEW']}><TrialBalancePage /></RequireAuth> },
      { path: 'general-ledger/account-ledger', element: <RequireAuth anyOf={['LEDGER_VIEW']}><AccountLedgerPage /></RequireAuth> },
      { path: 'general-ledger/entries/:id', element: <RequireAuth anyOf={['LEDGER_VIEW']}><EntryDetailPage /></RequireAuth> },
      { path: 'chart-of-accounts', element: <RequireAuth anyOf={['LEDGER_VIEW', 'ACCOUNT_ADMINISTER']}><ChartOfAccountsPage /></RequireAuth> },
      { path: 'fiscal-periods', element: <RequireAuth anyOf={['LEDGER_VIEW']}><FiscalPeriodsPage /></RequireAuth> },
      { path: 'close', element: <RequireAuth anyOf={['LEDGER_VIEW']}><CloseCalendarPage /></RequireAuth> },
      { path: 'general-ledger/open-items', element: <RequireAuth anyOf={['LEDGER_VIEW']}><OpenItemsPage /></RequireAuth> },
      { path: 'payables/suppliers', element: <RequireAuth anyOf={['AP_VIEW']}><SuppliersPage /></RequireAuth> },
      { path: 'payables/invoices', element: <RequireAuth anyOf={['AP_VIEW']}><InvoicesPage /></RequireAuth> },
      { path: 'payables/invoices/new', element: <RequireAuth anyOf={['AP_INVOICE_PREPARE']}><InvoiceFormPage /></RequireAuth> },
      { path: 'payables/invoices/:id', element: <RequireAuth anyOf={['AP_VIEW']}><InvoiceDetailPage /></RequireAuth> },
      { path: 'payables/invoices/:id/edit', element: <RequireAuth anyOf={['AP_INVOICE_PREPARE']}><InvoiceFormPage /></RequireAuth> },
      { path: 'payables/disbursements', element: <RequireAuth anyOf={['AP_VIEW']}><DisbursementVouchersPage /></RequireAuth> },
      { path: 'payables/disbursements/new', element: <RequireAuth anyOf={['DISBURSEMENT_PREPARE']}><NewDisbursementPage /></RequireAuth> },
      { path: 'payables/disbursements/:id', element: <RequireAuth anyOf={['AP_VIEW']}><DisbursementDetailPage /></RequireAuth> },
      { path: 'payables/reports', element: <RequireAuth anyOf={['AP_VIEW']}><PayablesReportsPage /></RequireAuth> },
      { path: 'receivables/customers', element: <RequireAuth anyOf={['AR_VIEW']}><CustomersPage /></RequireAuth> },
      { path: 'receivables/customers/:id', element: <RequireAuth anyOf={['AR_VIEW']}><CustomerDetailPage /></RequireAuth> },
      { path: 'receivables/billings', element: <RequireAuth anyOf={['AR_VIEW']}><BillingsPage /></RequireAuth> },
      { path: 'receivables/billings/new', element: <RequireAuth anyOf={['AR_BILLING_PREPARE']}><BillingFormPage /></RequireAuth> },
      { path: 'receivables/billings/:id', element: <RequireAuth anyOf={['AR_VIEW']}><BillingDetailPage /></RequireAuth> },
      { path: 'receivables/billings/:id/edit', element: <RequireAuth anyOf={['AR_BILLING_PREPARE']}><BillingFormPage /></RequireAuth> },
      { path: 'receivables/receipts', element: <RequireAuth anyOf={['AR_VIEW', 'COLLECTION_RECORD']}><ReceiptsPage /></RequireAuth> },
      { path: 'receivables/receipts/new', element: <RequireAuth anyOf={['COLLECTION_RECORD']}><NewReceiptPage /></RequireAuth> },
      { path: 'receivables/receipts/:id', element: <RequireAuth anyOf={['AR_VIEW', 'COLLECTION_RECORD']}><ReceiptDetailPage /></RequireAuth> },
      { path: 'receivables/reports', element: <RequireAuth anyOf={['AR_VIEW']}><ReceivablesReportsPage /></RequireAuth> },
      { path: 'treasury/deposits', element: <RequireAuth anyOf={['TREASURY_VIEW']}><DepositsPage /></RequireAuth> },
      { path: 'treasury/deposits/new', element: <RequireAuth anyOf={['DEPOSIT_PREPARE']}><NewDepositPage /></RequireAuth> },
      { path: 'treasury/deposits/:id', element: <RequireAuth anyOf={['TREASURY_VIEW']}><DepositDetailPage /></RequireAuth> },
      { path: 'treasury/daily-collections', element: <RequireAuth anyOf={['TREASURY_VIEW', 'COLLECTION_RECORD']}><CollectionReportPage /></RequireAuth> },
      { path: 'treasury/statements', element: <RequireAuth anyOf={['TREASURY_VIEW']}><BankStatementsPage /></RequireAuth> },
      { path: 'treasury/reconciliations/:id', element: <RequireAuth anyOf={['TREASURY_VIEW']}><ReconciliationPage /></RequireAuth> },
      { path: 'treasury/cash-position', element: <RequireAuth anyOf={['TREASURY_VIEW']}><CashPositionPage /></RequireAuth> },
      { path: 'fixed-assets', element: <RequireAuth anyOf={['FA_VIEW']}><AssetRegisterPage /></RequireAuth> },
      { path: 'fixed-assets/new', element: <RequireAuth anyOf={['FA_MANAGE']}><AssetFormPage /></RequireAuth> },
      { path: 'fixed-assets/setup', element: <RequireAuth anyOf={['FA_VIEW']}><AssetSetupPage /></RequireAuth> },
      { path: 'fixed-assets/reports', element: <RequireAuth anyOf={['FA_VIEW']}><FaReportsPage /></RequireAuth> },
      { path: 'fixed-assets/depreciation', element: <RequireAuth anyOf={['FA_VIEW']}><DepreciationRunsPage /></RequireAuth> },
      { path: 'fixed-assets/depreciation/:id', element: <RequireAuth anyOf={['FA_VIEW']}><DepreciationRunPage /></RequireAuth> },
      { path: 'fixed-assets/:id', element: <RequireAuth anyOf={['FA_VIEW']}><AssetDetailPage /></RequireAuth> },
      { path: 'inventory/items', element: <RequireAuth anyOf={['INVENTORY_VIEW']}><InventoryItemsPage /></RequireAuth> },
      { path: 'inventory/items/:id', element: <RequireAuth anyOf={['INVENTORY_VIEW']}><ItemMovementsPage /></RequireAuth> },
      { path: 'inventory/documents', element: <RequireAuth anyOf={['INVENTORY_VIEW']}><InventoryDocumentsPage /></RequireAuth> },
      { path: 'inventory/documents/new', element: <RequireAuth anyOf={['INVENTORY_MANAGE']}><NewInventoryDocumentPage /></RequireAuth> },
      { path: 'inventory/documents/:id', element: <RequireAuth anyOf={['INVENTORY_VIEW']}><InventoryDocumentPage /></RequireAuth> },
      { path: 'inventory/reports', element: <RequireAuth anyOf={['INVENTORY_VIEW', 'AP_VIEW']}><InventoryReportsPage /></RequireAuth> },
      { path: 'reports/financial-statements', element: <RequireAuth anyOf={['FINANCIAL_REPORT_VIEW']}><FinancialStatementsPage /></RequireAuth> },
      { path: 'reports/jobs', element: <RequireAuth anyOf={['FINANCIAL_REPORT_VIEW', 'FINANCIAL_REPORT_EXPORT', 'BUDGET_VIEW', 'AP_VIEW', 'AR_VIEW',
        'FA_VIEW', 'INVENTORY_VIEW', 'AUDIT_LOG_VIEW', 'SECURITY_EVENT_VIEW']}><ReportJobsPage /></RequireAuth> },
      { path: 'payables/suppliers/:id/bank', element: <RequireAuth anyOf={['SUPPLIER_MANAGE', 'SUPPLIER_BANK_VERIFY', 'AP_VIEW']}><SupplierBankPage /></RequireAuth> },
      { path: 'treasury/payment-files', element: <RequireAuth anyOf={['PAYMENT_RELEASE', 'TREASURY_VIEW']}><PaymentFilesPage /></RequireAuth> },
      { path: 'integrations', element: <RequireAuth anyOf={['INTEGRATION_VIEW']}><IntegrationsPage /></RequireAuth> },
      { path: 'tickets', element: <RequireAuth anyOf={['TICKET_CREATE', 'TICKET_VIEW_ALL', 'TICKET_WORK']}><TicketsPage /></RequireAuth> },
      { path: 'migration', element: <RequireAuth anyOf={['MIGRATION_PREPARE', 'MIGRATION_APPROVE']}><MigrationBatchesPage /></RequireAuth> },
      { path: 'migration/parallel-runs', element: <RequireAuth anyOf={['MIGRATION_PREPARE', 'MIGRATION_APPROVE']}><ParallelRunsPage /></RequireAuth> },
      { path: 'migration/parallel-runs/:id', element: <RequireAuth anyOf={['MIGRATION_PREPARE', 'MIGRATION_APPROVE']}><ParallelRunPage /></RequireAuth> },
      { path: 'migration/:id', element: <RequireAuth anyOf={['MIGRATION_PREPARE', 'MIGRATION_APPROVE']}><MigrationBatchPage /></RequireAuth> },
      { path: 'tickets/sla', element: <RequireAuth anyOf={['TICKET_ADMIN', 'TICKET_WORK', 'TICKET_VIEW_ALL']}><SlaSettingsPage /></RequireAuth> },
      { path: 'tickets/:id', element: <RequireAuth anyOf={['TICKET_CREATE', 'TICKET_VIEW_ALL', 'TICKET_WORK']}><TicketDetailPage /></RequireAuth> },
      { path: 'receivables/inquiries', element: <RequireAuth anyOf={['AR_VIEW']}><InquiriesPage /></RequireAuth> },
      { path: 'receivables/portal-access', element: <RequireAuth anyOf={['PORTAL_ACCESS_APPROVE', 'CUSTOMER_MANAGE']}><PortalAccessPage /></RequireAuth> },
      { path: 'budgets', element: <RequireAuth anyOf={['BUDGET_VIEW', 'BUDGET_PREPARE', 'BUDGET_APPROVE']}><BudgetsPage /></RequireAuth> },
      { path: 'budgets/:id', element: <RequireAuth anyOf={['BUDGET_VIEW', 'BUDGET_PREPARE', 'BUDGET_APPROVE']}><BudgetDetailPage /></RequireAuth> },
      { path: 'finance-settings', element: <RequireAuth anyOf={['LEDGER_VIEW', 'ACCOUNT_ADMINISTER', 'BANK_ACCOUNT_MANAGE']}><FinanceSettingsPage /></RequireAuth> },
      { path: 'audit', element: <RequireAuth anyOf={['AUDIT_LOG_VIEW', 'SECURITY_EVENT_VIEW']}><AuditPage /></RequireAuth> },
      { path: 'audit/anomalies', element: <RequireAuth anyOf={['ANOMALY_VIEW']}><AnomaliesPage /></RequireAuth> },
      { path: '*', element: <p className="text-sm">Page not found.</p> },
    ],
  },
  {
    path: '/portal',
    element: <RequireAuth portal><PortalLayout /></RequireAuth>,
    children: [
      { index: true, element: <PortalHomePage /> },
      { path: 'profile', element: <ProfilePage portal /> },
      { path: 'billings/:id', element: <PortalBillingPage /> },
    ],
  },
];

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
      refetchOnWindowFocus: false,
    },
    mutations: { retry: false },
  },
});

const router = createBrowserRouter(routes);

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  );
}
