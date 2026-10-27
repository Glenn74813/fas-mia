import { api, downloadFile } from './client';
import type {
  TicketCategory,
  Clearing, ClearingProposal, CloseCalendarChecklist, CloseFrequency, CloseScheduleItem, CloseTask, GlOpenItem, OpenItemAccount,
  AccountLedger, ChainVerification, ChartSummary, CloseChecklist, CostCenter, CurrentUser, DocumentInfo, EntryDetail,
  AdHocDataset, AdHocDefinition, AdHocResult, AdHocSaved, AllocationCycle, AllocationPreview, AllocationRun, NrvReview,
  AnomalyFinding, AnomalySummary, ApiClient, InterfaceBatch, InterfaceType, DataExport, LegalHold, RetentionSchedule, WebhookDelivery, WebhookSubscription, EventRecord, FiscalYear, LineInput, LoginResponse, Page, Period, PeriodChange, Permission, RecurringJournal, Role,
  RoleAssignmentResult, RoleChangeResult, SodConflict, SubsidiaryAccount, TokenResponse, TotpEnrollment, TrialBalance,
  UserDetail, UserSummary, VoucherDetail, VoucherInput, VoucherSummary,
  AccountMapping, AgingRow, BankAccount, BudgetAdjustment, BudgetView, DvSummary, DvView, InvoiceInput, InvoiceSummary,
  InvoiceView, Supplier, TaxCode,
  ArAgingRow, BillingInput, BillingSummary, BillingView, Contract, ControlReconciliation, Customer, CustomerInput, OpenItem,
  PortalAccount, PortalBilling, PortalUser, ReceiptInput, ReceiptSummary, ReceiptView, Statement,
  CashForecast, CashPosition, CheckRow, DailyCollectionReport, DepositSummary, DepositView, ReconciliationView, StatementLine,
  StatementSummary, UndepositedReceipt,
  AssetCategory, AssetInput, AssetSummary, AssetView, AucProject, DepreciationRun, DepreciationRunSummary, DisposalView, PpeRow,
  ReportJob, StatementResult, StatementTemplate, PortalLinkRequest, Dashboard, Inbox, AppNotification, RoleGrant, AssetTransfer, Organization, OfficeAssignment, AccessReviewCycle, AccessReviewCycleDetail, AccessReviewItem, ApprovalLimit, PortalInquiry, ReportSchedule, ParallelRunDetail, ParallelRunSummary, MigrationBatchDetail, MigrationBatchSummary, MigrationKind, BusinessCalendar, SlaPolicy, TicketSummary, TicketView, EligibleDv, IntegrationChannel, PaymentBatch, SupplierBankAccount, Transmission,
  GrIrStatus, InventoryDocument, InventoryDocumentSummary, InventoryItem, InventoryKind, InventoryLineInput, InventoryMovement, Warehouse,
} from './types';

const noAuth = { auth: false } as const;

export const authApi = {
  login: (identifier: string, password: string) =>
    api.post<LoginResponse>('/auth/login', { identifier, password }, noAuth),
  enrollTotp: (mfaToken: string) => api.post<TotpEnrollment>('/auth/mfa/totp/enroll', { mfaToken }, noAuth),
  activateTotp: (mfaToken: string, code: string) =>
    api.post<TokenResponse>('/auth/mfa/totp/activate', { mfaToken, code }, noAuth),
  verifyTotp: (mfaToken: string, code: string) => api.post<TokenResponse>('/auth/mfa/verify', { mfaToken, code }, noAuth),
  sendEmailCode: (mfaToken: string) =>
    api.post<{ sentTo: string; expiresAt: string }>('/auth/mfa/email/send', { mfaToken }, noAuth),
  verifyEmailCode: (mfaToken: string, code: string) =>
    api.post<TokenResponse>('/auth/mfa/email/verify', { mfaToken, code }, noAuth),
  passkeySignInOptions: (mfaToken: string) =>
    api.post<Record<string, unknown>>('/auth/mfa/passkey/options', { mfaToken }, noAuth),
  passkeySignIn: (mfaToken: string, credential: Record<string, unknown>) =>
    api.post<TokenResponse>('/auth/mfa/passkey/verify', { mfaToken, credential }, noAuth),
  passkeys: () => api.get<{ id: string; name: string; createdAt: string; lastUsedAt: string | null }[]>('/auth/passkeys'),
  passkeyRegistrationOptions: (code: string) => api.post<Record<string, unknown>>('/auth/passkeys/options', { code }),
  registerPasskey: (name: string, credential: Record<string, unknown>) => api.post<{ id: string; name: string }>('/auth/passkeys', { name, credential }),
  removePasskey: (id: string) => api.delete<void>(`/auth/passkeys/${id}`),
  recover: (mfaToken: string, recoveryCode: string) =>
    api.post<LoginResponse>('/auth/mfa/recovery', { mfaToken, recoveryCode }, noAuth),
  regenerateRecoveryCodes: (code: string) => api.post<{ codes: string[] }>('/auth/recovery-codes', { code }),
  devBypass: (mfaToken: string) => api.post<TokenResponse>('/auth/mfa/dev-bypass', { mfaToken }, noAuth),
  me: () => api.get<CurrentUser>('/auth/me'),
  changePassword: (currentPassword: string, newPassword: string) =>
    api.post<void>('/auth/password/change', { currentPassword, newPassword }),
  forgotPassword: (identifier: string) => api.post<void>('/auth/password/forgot', { identifier }, noAuth),
  resetPassword: (token: string, newPassword: string) =>
    api.post<void>('/auth/password/reset', { token, newPassword }, noAuth),
};

export interface UserQuery {
  q?: string;
  status?: string;
  page: number;
  size: number;
  sort?: string;
}

function qs(params: Record<string, string | number | undefined>): string {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== '') p.set(k, String(v));
  });
  const s = p.toString();
  return s ? `?${s}` : '';
}

export const userApi = {
  list: (q: UserQuery) => api.get<Page<UserSummary>>(`/users${qs({ ...q })}`),
  get: (id: string) => api.get<UserDetail>(`/users/${id}`),
  create: (body: {
    username: string; email: string; displayName: string; userType: string; temporaryPassword: string;
    roleCodes: string[]; accessExpiresAt?: string | null;
  }) => api.post<RoleAssignmentResult>('/users', body),
  update: (id: string, body: { version: number; email: string; displayName: string; accessExpiresAt: string | null }) =>
    api.put<UserDetail>(`/users/${id}`, body),
  activate: (id: string, version: number) => api.post<UserDetail>(`/users/${id}/activate`, { version }),
  deactivate: (id: string, version: number) => api.post<UserDetail>(`/users/${id}/deactivate`, { version }),
  unlock: (id: string) => api.post<UserDetail>(`/users/${id}/unlock`),
  setRoles: (id: string, version: number, roleCodes: string[]) =>
    api.put<RoleAssignmentResult>(`/users/${id}/roles`, { version, roleCodes }),
  revokeSessions: (id: string) => api.post<{ sessionsRevoked: number }>(`/users/${id}/sessions/revoke`),
  resetMfa: (id: string) => api.post<UserDetail>(`/users/${id}/mfa/reset`),
  temporaryPassword: (id: string, temporaryPassword: string) =>
    api.post<UserDetail>(`/users/${id}/password/temporary`, { temporaryPassword }),
};

export const roleApi = {
  list: () => api.get<Role[]>('/roles'),
  get: (code: string) => api.get<Role>(`/roles/${code}`),
  create: (body: { code: string; name: string; description?: string; permissionCodes: string[] }) =>
    api.post<RoleChangeResult>('/roles', body),
  setPermissions: (code: string, version: number, permissionCodes: string[]) =>
    api.put<RoleChangeResult>(`/roles/${code}/permissions`, { version, permissionCodes }),
  permissions: () => api.get<Permission[]>('/permissions'),
  sodRules: () => api.get<SodConflict[]>('/sod-rules'),
};

export interface EventQuery {
  action?: string;
  actorUserId?: string;
  resourceType?: string;
  from?: string;
  to?: string;
  page: number;
  size: number;
}

export const auditApi = {
  auditEvents: (q: EventQuery) => api.get<Page<EventRecord>>(`/audit-events${qs({ ...q })}`),
  securityEvents: (q: EventQuery) => api.get<Page<EventRecord>>(`/security-events${qs({ ...q })}`),
  verify: () => api.post<{ audit: ChainVerification; security: ChainVerification }>('/audit-events/verification'),
};

export const anomalyApi = {
  list: (q: { status?: string; severity?: string; rule?: string; page?: number; size?: number }) =>
    api.get<Page<AnomalyFinding>>(`/anomalies${qs({ ...q })}`),
  summary: () => api.get<AnomalySummary>('/anomalies/summary'),
  run: () => api.post<{ high: number; medium: number; low: number }>('/anomalies/run'),
  review: (id: string, body: { version: number; status: 'EXPLAINED' | 'ESCALATED'; note: string }) =>
    api.post<AnomalyFinding>(`/anomalies/${id}/review`, body),
};

export const dataExportApi = {
  list: (page = 0) => api.get<Page<DataExport>>(`/data-exports?page=${page}&size=25`),
  request: (purpose: string, fiscalYearId?: string) => api.post<DataExport>('/data-exports', { purpose, fiscalYearId }),
  decide: (id: string, body: { approve: boolean; note?: string; version: number }) =>
    api.post<DataExport>(`/data-exports/${id}/decision`, body),
  download: (e: DataExport) => downloadFile(`/data-exports/${e.id}/download`, e.fileName ?? 'miaa-cas-export.zip'),
};

export const retentionApi = {
  schedule: () => api.get<RetentionSchedule>('/records-retention'),
  hold: (body: { fiscalYearId?: string; reason: string; reference?: string }) => api.post<LegalHold>('/records-retention/holds', body),
  release: (id: string, body: { note: string; version: number }) => api.post<LegalHold>(`/records-retention/holds/${id}/release`, body),
};

export const apiAccessApi = {
  catalog: () => api.get<{ scopes: string[]; events: string[] }>('/api-access/catalog'),
  clients: () => api.get<ApiClient[]>('/api-clients'),
  createClient: (body: { name: string; description?: string; scopes: string[]; expiresAt?: string }) =>
    api.post<{ client: ApiClient; clientSecret: string }>('/api-clients', body),
  rotateClient: (c: ApiClient) => api.post<{ client: ApiClient; clientSecret: string }>(`/api-clients/${c.id}/rotate-secret`, { version: c.version }),
  revokeClient: (c: ApiClient, reason: string) => api.post<ApiClient>(`/api-clients/${c.id}/revoke`, { reason, version: c.version }),
  webhooks: () => api.get<WebhookSubscription[]>('/webhooks'),
  subscribe: (body: { name: string; url: string; events: string[] }) =>
    api.post<{ subscription: WebhookSubscription; signingSecret: string }>('/webhooks', body),
  setStatus: (w: WebhookSubscription, status: 'ACTIVE' | 'PAUSED') => api.post<WebhookSubscription>(`/webhooks/${w.id}/status`, { status, version: w.version }),
  test: (w: WebhookSubscription) => api.post<WebhookDelivery>(`/webhooks/${w.id}/test`),
  deliveries: (id: string) => api.get<Page<WebhookDelivery>>(`/webhooks/${id}/deliveries?size=25`),
  redeliver: (id: string) => api.post<WebhookDelivery>(`/webhooks/deliveries/${id}/redeliver`),
};

export const interfaceApi = {
  list: (q: { type?: string; status?: string; page?: number }) => api.get<Page<InterfaceBatch>>(`/interfaces/batches${qs({ ...q, size: 25 })}`),
  get: (id: string) => api.get<InterfaceBatch>(`/interfaces/batches/${id}`),
  upload: (type: InterfaceType, file: File, params: Record<string, string>) =>
    api.upload<InterfaceBatch>(`/interfaces/${type}/batches/upload${qs(params)}`, file),
  accept: (b: InterfaceBatch) => api.post<InterfaceBatch>(`/interfaces/batches/${b.id}/accept`, { version: b.version }),
  reject: (b: InterfaceBatch, note: string) => api.post<InterfaceBatch>(`/interfaces/batches/${b.id}/reject`, { note, version: b.version }),
};

export const nrvApi = {
  list: () => api.get<NrvReview[]>('/inventory/nrv-reviews'),
  get: (id: string) => api.get<NrvReview>(`/inventory/nrv-reviews/${id}`),
  create: (asOf: string) => api.post<NrvReview>('/inventory/nrv-reviews', { asOf }),
  prices: (r: NrvReview, prices: { itemCode: string; marketUnitValue: string | null; source?: string }[]) =>
    api.put<NrvReview>(`/inventory/nrv-reviews/${r.id}/prices`, { version: r.version, prices }),
  propose: (r: NrvReview, note: string) => api.post<NrvReview>(`/inventory/nrv-reviews/${r.id}/propose`, { version: r.version, note }),
  cancel: (r: NrvReview) => api.post<NrvReview>(`/inventory/nrv-reviews/${r.id}/cancel`, { version: r.version }),
};

export const allocationApi = {
  list: () => api.get<AllocationCycle[]>('/allocation-cycles'),
  create: (body: unknown) => api.post<AllocationCycle>('/allocation-cycles', body),
  update: (id: string, body: unknown) => api.put<AllocationCycle>(`/allocation-cycles/${id}`, body),
  setStatus: (c: AllocationCycle, status: 'ACTIVE' | 'INACTIVE') => api.post<AllocationCycle>(`/allocation-cycles/${c.id}/status`, { status, version: c.version }),
  preview: (id: string, periodId: string) => api.get<AllocationPreview>(`/allocation-cycles/${id}/preview?periodId=${periodId}`),
  run: (id: string, periodId: string) => api.post<AllocationRun>(`/allocation-cycles/${id}/runs`, { periodId }),
  runs: (id: string) => api.get<AllocationRun[]>(`/allocation-cycles/${id}/runs`),
};

export const adHocApi = {
  datasets: () => api.get<AdHocDataset[]>('/ad-hoc-reports/datasets'),
  preview: (dataset: string, definition: AdHocDefinition) => api.post<AdHocResult>('/ad-hoc-reports/preview', { dataset, definition }),
  saved: () => api.get<AdHocSaved[]>('/ad-hoc-reports'),
  save: (body: { name: string; dataset: string; definition: AdHocDefinition; shared: boolean; version?: number }, id?: string) =>
    id ? api.put<AdHocSaved>(`/ad-hoc-reports/${id}`, body) : api.post<AdHocSaved>('/ad-hoc-reports', body),
  remove: (id: string) => api.delete<void>(`/ad-hoc-reports/${id}`),
  export: (s: AdHocSaved, format: 'CSV' | 'XLSX' | 'PDF') =>
    api.post<{ id: string }>('/report-jobs', { reportType: 'AD_HOC', format, parameters: { definitionId: s.id, dataset: s.dataset } }),
};

export const accountApi = {
  chart: () => api.get<ChartSummary>('/accounts'),
  create: (body: Record<string, unknown>) => api.post<ChartSummary['accounts'][number]>('/accounts', body),
  importCsv: (file: File, placeholder: boolean) =>
    api.upload<{ created: number }>(`/accounts/import?placeholder=${placeholder}`, file),
  costCenters: () => api.get<CostCenter[]>('/cost-centers'),
  subsidiaryAccounts: (slType?: string, q?: string, page = 0, size = 50) =>
    api.get<Page<SubsidiaryAccount>>(`/subsidiary-accounts${qs({ slType, q: q || undefined, page, size })}`),
  subsidiaryAccount: (id: string) => api.get<SubsidiaryAccount>(`/subsidiary-accounts/${id}`),
};

export const periodApi = {
  fiscalYears: () => api.get<FiscalYear[]>('/fiscal-years'),
  createFiscalYear: (fiscalYear: number) => api.post<FiscalYear>('/fiscal-years', { fiscalYear }),
  closeFiscalYear: (id: string, version: number) => api.post<FiscalYear>(`/fiscal-years/${id}/close`, { version }),
  open: (p: Period) => api.post<Period>(`/accounting-periods/${p.id}/open`, { version: p.version }),
  softClose: (p: Period) => api.post<Period>(`/accounting-periods/${p.id}/soft-close`, { version: p.version }),
  checklist: (id: string) => api.get<CloseChecklist>(`/accounting-periods/${id}/close-checklist`),
  close: (p: Period, waivedChecks: string[], justification: string) =>
    api.post<Period>(`/accounting-periods/${p.id}/close`, { version: p.version, waivedChecks, justification }),
  history: (id: string) => api.get<PeriodChange[]>(`/accounting-periods/${id}/history`),
  requestReopen: (id: string, reason: string) => api.post<PeriodChange>(`/accounting-periods/${id}/reopen-requests`, { reason }),
  pendingReopens: () => api.get<PeriodChange[]>('/period-reopen-requests'),
  decideReopen: (id: string, approve: boolean, note: string) =>
    api.post<PeriodChange>(`/period-reopen-requests/${id}/decision`, { approve, note }),
  closingVoucher: (fiscalYearId: string, surplusAccountId: string) =>
    api.post<VoucherDetail>(`/fiscal-years/${fiscalYearId}/closing-voucher`, { surplusAccountId }),
};

export interface VoucherQuery {
  status?: string;
  type?: string;
  q?: string;
  mine?: boolean;
  page: number;
  size: number;
  sort?: string;
}

export const voucherApi = {
  list: (q: VoucherQuery) =>
    api.get<Page<VoucherSummary>>(`/journal-vouchers${qs({ ...q, mine: q.mine ? 'true' : undefined })}`),
  get: (id: string) => api.get<VoucherDetail>(`/journal-vouchers/${id}`),
  create: (body: VoucherInput) => api.post<VoucherDetail>('/journal-vouchers', body),
  update: (id: string, version: number, body: VoucherInput) =>
    api.put<VoucherDetail>(`/journal-vouchers/${id}`, { ...body, version }),
  act: (id: string, action: 'submit' | 'approve' | 'return' | 'reject' | 'cancel', version: number, comment?: string) =>
    api.post<VoucherDetail>(`/journal-vouchers/${id}/${action}`, { version, comment: comment || null }),
  /** The same key must be reused when retrying one posting attempt so the server can deduplicate it. */
  post: (id: string, version: number, idempotencyKey: string) =>
    api.post<VoucherDetail>(`/journal-vouchers/${id}/post`, { version }, { headers: { 'Idempotency-Key': idempotencyKey } }),
  upload: (id: string, file: File) => api.upload<DocumentInfo>(`/journal-vouchers/${id}/documents`, file),
  removeDocument: (documentId: string) => api.post<DocumentInfo>(`/documents/${documentId}/remove`),
  download: (doc: DocumentInfo) => downloadFile(`/documents/${doc.id}/content`, doc.fileName),
  reverse: (entryId: string, accountingDate: string, reason: string) =>
    api.post<VoucherDetail>(`/journal-entries/${entryId}/reversal`, { accountingDate, adjustmentPeriod: false, reason }),
};

export const ledgerApi = {
  trialBalance: (periodId: string) => api.get<TrialBalance>(`/general-ledger/trial-balance?periodId=${periodId}`),
  accountLedger: (accountId: string, from: string, to: string, page: number) =>
    api.get<AccountLedger>(`/general-ledger/accounts/${accountId}/ledger${qs({ from, to, page, size: 100 })}`),
  entry: (id: string) => api.get<EntryDetail>(`/general-ledger/entries/${id}`),
};

export const clearingApi = {
  accounts: () => api.get<OpenItemAccount[]>('/open-items/accounts'),
  setManaged: (accountId: string, managed: boolean) => api.put<void>(`/accounts/${accountId}/open-item-managed`, { managed }),
  items: (accountId: string, page: number) => api.get<Page<GlOpenItem>>(`/open-items/accounts/${accountId}/items${qs({ page, size: 100 })}`),
  clear: (accountId: string, lineIds: string[], note: string) =>
    api.post<Clearing>(`/open-items/accounts/${accountId}/clearings`, { lineIds, note }),
  autoClear: (accountId: string, rule: 'REFERENCE' | 'AMOUNT', apply: boolean) =>
    api.post<ClearingProposal[]>(`/open-items/accounts/${accountId}/auto-clear`, { rule, apply }),
  history: (accountId: string) => api.get<Clearing[]>(`/open-items/accounts/${accountId}/clearings`),
  reverse: (id: string, reason: string) => api.post<Clearing>(`/clearings/${id}/reverse`, { reason }),
};

type CloseTaskInput = { code: string; name: string; frequency: CloseFrequency; dueOffsetDays: number; responsiblePermission: string;
  instructions: string; sortOrder: number };

export const closeApi = {
  checklist: (frequency: CloseFrequency, scope: string) => api.get<CloseCalendarChecklist>(`/close/checklists${qs({ frequency, scope })}`),
  complete: (taskId: string, scopeKey: string, outcome: 'DONE' | 'NOT_APPLICABLE', note: string) =>
    api.post<CloseCalendarChecklist>(`/close/tasks/${taskId}/complete`, { scopeKey, outcome, note }),
  schedule: (from: string, to: string) => api.get<CloseScheduleItem[]>(`/close/schedule${qs({ from, to })}`),
  tasks: () => api.get<CloseTask[]>('/close/tasks'),
  createTask: (body: CloseTaskInput) => api.post<CloseTask>('/close/tasks', body),
  updateTask: (id: string, body: CloseTaskInput & { version: number; status: 'ACTIVE' | 'INACTIVE' }) => api.put<CloseTask>(`/close/tasks/${id}`, body),
};

export const recurringApi = {
  list: (page = 0) => api.get<Page<RecurringJournal>>(`/recurring-journals${qs({ page, size: 50 })}`),
  create: (body: { name: string; description: string; frequency: string; dayOfMonth: number; firstRunDate: string;
    endDate: string | null; lines: LineInput[] }) => api.post<RecurringJournal>('/recurring-journals', body),
  setActive: (r: RecurringJournal, active: boolean) =>
    api.post<RecurringJournal>(`/recurring-journals/${r.id}/${active ? 'resume' : 'pause'}`, { version: r.version }),
  runDue: () => api.post<{ generated: number; skipped: string[] }>('/recurring-journals/run-due'),
};

// ---------------------------------------------------------------- Phase 3a

type Act = 'submit' | 'approve' | 'return' | 'reject' | 'cancel' | 'post';

export const supplierApi = {
  list: (q?: string, page = 0, size = 25, sort?: string) => api.get<Page<Supplier>>(`/suppliers${qs({ q: q || undefined, page, size, sort })}`),
  get: (id: string) => api.get<Supplier>(`/suppliers/${id}`),
  create: (body: { code: string; name: string; tin: string | null; address: string | null; vatRegistered: boolean;
    defaultEwtCodeId: string | null }) => api.post<Supplier>('/suppliers', body),
};

export const invoiceApi = {
  list: (q: { status?: string; q?: string; supplierId?: string; page: number; size: number; sort?: string }) =>
    api.get<Page<InvoiceSummary>>(`/accounts-payable/invoices${qs({ ...q })}`),
  get: (id: string) => api.get<InvoiceView>(`/accounts-payable/invoices/${id}`),
  create: (body: InvoiceInput) => api.post<InvoiceView>('/accounts-payable/invoices', body),
  update: (id: string, version: number, body: InvoiceInput) =>
    api.put<InvoiceView>(`/accounts-payable/invoices/${id}?version=${version}`, body),
  act: (id: string, action: Act, version: number, comment?: string) =>
    api.post<InvoiceView>(`/accounts-payable/invoices/${id}/${action}`, { version, comment: comment || null }),
  upload: (id: string, file: File) => api.upload<DocumentInfo>(`/accounts-payable/invoices/${id}/documents`, file),
};

export const dvApi = {
  list: (q: { status?: string; page: number; size: number; sort?: string }) => api.get<Page<DvSummary>>(`/disbursement-vouchers${qs({ ...q })}`),
  get: (id: string) => api.get<DvView>(`/disbursement-vouchers/${id}`),
  create: (body: { supplierId: string; particulars: string; allocations: { invoiceId: string; amount: string }[] }) =>
    api.post<DvView>('/disbursement-vouchers', body),
  act: (id: string, action: Act, version: number, comment?: string) =>
    api.post<DvView>(`/disbursement-vouchers/${id}/${action}`, { version, comment: comment || null }),
  instruct: (id: string, body: { version: number; mode: string; bankAccountId: string; reference: string; paymentDate: string },
    idempotencyKey: string) => api.post<DvView>(`/disbursement-vouchers/${id}/payment-instruction`, body, { headers: { 'Idempotency-Key': idempotencyKey } }),
  voidPayment: (id: string, version: number, reason: string) =>
    api.post<DvView>(`/disbursement-vouchers/${id}/payment-void`, { version, reason }),
  confirm: (id: string, version: number, releasedOn: string) =>
    api.post<DvView>(`/disbursement-vouchers/${id}/payment-confirmation`, { version, releasedOn }),
  approveCheck: (id: string, version: number) => api.post<DvView>(`/disbursement-vouchers/${id}/check-approval`, { version }),
  upload: (id: string, file: File) => api.upload<DocumentInfo>(`/disbursement-vouchers/${id}/documents`, file),
};

export const apReportApi = {
  aging: (asOf: string) => api.get<{ asOf: string; rows: AgingRow[]; totals: AgingRow }>(`/accounts-payable/aging?asOf=${asOf}`),
  reconciliation: () => api.get<{ controlAccountCode: string; subledgerOpenBalance: string; generalLedgerBalance: string;
    difference: string; reconciled: boolean }>('/accounts-payable/reconciliation'),
  withholding: (year: number, quarter: number) => api.get<{ year: number; quarter: number; note: string; rows: {
    supplierTin: string | null; supplierName: string; taxCode: string; month1Base: string; month2Base: string;
    month3Base: string; totalBase: string; taxWithheld: string }[] }>(`/accounts-payable/withholding-schedule?year=${year}&quarter=${quarter}`),
  advances: () => api.get<{ note: string; totalOutstanding: string; rows: { id: string; supplierCode: string; supplierName: string; reference: string;
    advanceDate: string; description: string; amount: string; outstandingAmount: string; status: string; openingEntryNumber: string }[];
    reconciliation: { controlAccountCode: string; subledgerOpenBalance: string; generalLedgerBalance: string; difference: string;
      reconciled: boolean } | null }>('/accounts-payable/advances'),
};

export const budgetApi = {
  list: (page = 0) => api.get<Page<BudgetView>>(`/budgets${qs({ page, size: 25 })}`),
  get: (id: string) => api.get<BudgetView>(`/budgets/${id}`),
  create: (fiscalYearId: string, name: string) => api.post<BudgetView>('/budgets', { fiscalYearId, name }),
  setAllocations: (id: string, version: number, allocations: { accountId: string; costCenterId: string | null; amount: string }[]) =>
    api.put<BudgetView>(`/budgets/${id}/allocations`, { version, allocations }),
  act: (id: string, action: 'submit' | 'approve' | 'return', version: number, comment?: string) =>
    api.post<BudgetView>(`/budgets/${id}/${action}`, { version, comment: comment || null }),
  adjustments: (id: string) => api.get<BudgetAdjustment[]>(`/budgets/${id}/adjustments`),
  requestAdjustment: (id: string, body: { fromAllocationId: string | null; toAllocationId: string; amount: string; reason: string }) =>
    api.post<BudgetAdjustment>(`/budgets/${id}/adjustments`, body),
  decideAdjustment: (adjustmentId: string, approve: boolean) =>
    api.post<BudgetAdjustment>(`/budgets/adjustments/${adjustmentId}/decision`, { approve }),
};

export const settingsApi = {
  mappings: () => api.get<AccountMapping[]>('/account-mappings'),
  setMapping: (key: string, accountId: string) => api.put<AccountMapping>(`/account-mappings/${key}`, { accountId }),
  taxCodes: () => api.get<TaxCode[]>('/tax-codes'),
  createTaxCode: (body: { code: string; description: string; taxType: string; rate: string; accountId: string }) =>
    api.post<TaxCode>('/tax-codes', body),
  bankAccounts: () => api.get<BankAccount[]>('/bank-accounts'),
  createBankAccount: (body: { code: string; bankName: string; accountName: string; accountNumberLast4: string; glAccountId: string }) =>
    api.post<BankAccount>('/bank-accounts', body),
};

// ---------------------------------------------------------------- Phase 3b

export const customerApi = {
  list: (q?: string, page = 0, size = 25, sort?: string) => api.get<Page<Customer>>(`/customers${qs({ q: q || undefined, page, size, sort })}`),
  get: (id: string) => api.get<Customer>(`/customers/${id}`),
  create: (body: CustomerInput) => api.post<Customer>('/customers', body),
  update: (id: string, body: Omit<CustomerInput, 'code'> & { version: number; status: string }) =>
    api.put<Customer>(`/customers/${id}`, body),
  openItems: (id: string) => api.get<OpenItem[]>(`/customers/${id}/open-items`),
  contracts: (id: string) => api.get<Contract[]>(`/customers/${id}/contracts`),
  createContract: (id: string, body: { contractNumber: string; description: string; startDate: string; endDate: string | null;
    monthlyAmount: string; revenueAccountId: string; costCenterId: string | null; vatCodeId: string | null }) =>
    api.post<Contract>(`/customers/${id}/contracts`, body),
  setContractStatus: (c: Contract, status: string) =>
    api.put<Contract>(`/customer-contracts/${c.id}/status`, { version: c.version, status }),
  portalUsers: (id: string) => api.get<PortalUser[]>(`/customers/${id}/portal-users`),
  unlinkPortalUser: (id: string, userId: string) => api.delete<PortalUser[]>(`/customers/${id}/portal-users/${userId}`),
  statement: (id: string, from: string, to: string) => api.get<Statement>(`/customers/${id}/statement${qs({ from, to })}`),
};

export const billingApi = {
  list: (q: { kind?: string; status?: string; customerId?: string; q?: string; page: number; size: number; sort?: string }) =>
    api.get<Page<BillingSummary>>(`/accounts-receivable/billings${qs({ ...q })}`),
  get: (id: string) => api.get<BillingView>(`/accounts-receivable/billings/${id}`),
  create: (body: BillingInput) => api.post<BillingView>('/accounts-receivable/billings', body),
  update: (id: string, version: number, body: BillingInput) =>
    api.put<BillingView>(`/accounts-receivable/billings/${id}?version=${version}`, body),
  act: (id: string, action: Act, version: number, comment?: string) =>
    api.post<BillingView>(`/accounts-receivable/billings/${id}/${action}`, { version, comment: comment || null }),
  upload: (id: string, file: File) => api.upload<DocumentInfo>(`/accounts-receivable/billings/${id}/documents`, file),
  run: (billingPeriod: string, documentDate: string) => api.post<{ billingPeriod: string; created: BillingSummary[];
    skipped: string[] }>('/accounts-receivable/billing-runs', { billingPeriod, documentDate }),
};

export const receiptApi = {
  list: (q: { status?: string; customerId?: string; page: number; size: number; sort?: string }) =>
    api.get<Page<ReceiptSummary>>(`/receipts${qs({ ...q })}`),
  get: (id: string) => api.get<ReceiptView>(`/receipts/${id}`),
  record: (body: ReceiptInput, idempotencyKey: string) => api.post<ReceiptView>('/receipts', body, { headers: { 'Idempotency-Key': idempotencyKey } }),
  post: (id: string, version: number) => api.post<ReceiptView>(`/receipts/${id}/post`, { version }),
  cancel: (id: string, version: number, reason: string) => api.post<ReceiptView>(`/receipts/${id}/cancel`, { version, reason }),
  apply: (id: string, version: number, accountingDate: string, allocations: { documentId: string; amount: string }[], idempotencyKey: string) =>
    api.post<ReceiptView>(`/receipts/${id}/applications`, { version, accountingDate, allocations }, { headers: { 'Idempotency-Key': idempotencyKey } }),
  upload: (id: string, file: File) => api.upload<DocumentInfo>(`/receipts/${id}/documents`, file),
};

export const arReportApi = {
  aging: (asOf: string) => api.get<{ asOf: string; rows: ArAgingRow[]; totals: ArAgingRow }>(`/accounts-receivable/aging?asOf=${asOf}`),
  reconciliation: () => api.get<{ receivables: ControlReconciliation; unappliedCollections: ControlReconciliation;
    reconciled: boolean }>('/accounts-receivable/reconciliation'),
};

export const portalApi = {
  account: () => api.get<PortalAccount>('/portal/account'),
  billings: (page: number) => api.get<Page<BillingSummary>>(`/portal/billings${qs({ page, size: 25 })}`),
  billing: (id: string) => api.get<PortalBilling>(`/portal/billings/${id}`),
  receipts: (page: number) => api.get<Page<ReceiptSummary>>(`/portal/receipts${qs({ page, size: 25 })}`),
  statement: (from: string, to: string) => api.get<Statement>(`/portal/statement${qs({ from, to })}`),
  billingPdf: (id: string, number: string) => downloadFile(`/portal/billings/${id}/pdf`, `${number}.pdf`),
  statementPdf: (from: string, to: string) => downloadFile(`/portal/statement/pdf${qs({ from, to })}`, `statement_${from}_${to}.pdf`),
  inquiries: (page = 0) => api.get<Page<PortalInquiry>>(`/portal/inquiries${qs({ page, size: 25 })}`),
  inquiry: (id: string) => api.get<PortalInquiry>(`/portal/inquiries/${id}`),
  submitInquiry: (body: { kind: string; subject: string; message: string; billingId?: string; paymentDate?: string; paymentAmount?: string;
    paymentMethod?: string; paymentReference?: string }) => api.post<PortalInquiry>('/portal/inquiries', body),
  attachToInquiry: (id: string, file: File) => api.upload<DocumentInfo>(`/portal/inquiries/${id}/documents`, file),
};

export const inquiryApi = {
  list: (status?: string, page = 0) => api.get<Page<PortalInquiry>>(`/portal-inquiries${qs({ status, page, size: 25 })}`),
  get: (id: string) => api.get<PortalInquiry>(`/portal-inquiries/${id}`),
  respond: (i: PortalInquiry, response: string) => api.post<PortalInquiry>(`/portal-inquiries/${i.id}/response`, { version: i.version, response }),
  close: (i: PortalInquiry) => api.post<PortalInquiry>(`/portal-inquiries/${i.id}/close`, { version: i.version }),
};

// ---------------------------------------------------------------- Phase 3c

export const treasuryApi = {
  undeposited: () => api.get<UndepositedReceipt[]>('/treasury/undeposited-receipts'),
  deposits: (status?: string, page = 0) => api.get<Page<DepositSummary>>(`/deposits${qs({ status, page, size: 25 })}`),
  deposit: (id: string) => api.get<DepositView>(`/deposits/${id}`),
  createDeposit: (body: { bankAccountId: string; depositDate: string; bankReference: string | null; receiptIds: string[] }) =>
    api.post<DepositView>('/deposits', body),
  postDeposit: (id: string, version: number) => api.post<DepositView>(`/deposits/${id}/post`, { version }),
  cancelDeposit: (id: string, version: number, reason: string) => api.post<DepositView>(`/deposits/${id}/cancel`, { version, reason }),
  uploadDeposit: (id: string, file: File) => api.upload<DocumentInfo>(`/deposits/${id}/documents`, file),
  dcr: (date: string, collector?: string) => api.get<DailyCollectionReport>(`/treasury/daily-collection-report${qs({ date, collector })}`),
  cashPosition: (asOf: string) => api.get<CashPosition>(`/treasury/cash-position?asOf=${asOf}`),
  cashForecast: (q: { from?: string; granularity: 'WEEK' | 'MONTH'; periods: number; includeBudget: boolean }) =>
    api.get<CashForecast>(`/treasury/cash-forecast${qs({ ...q, includeBudget: String(q.includeBudget) })}`),
  checks: (q: { bankAccountId?: string; status?: string; asOf: string }) => api.get<CheckRow[]>(`/treasury/checks${qs({ ...q })}`),
};

export const bankApi = {
  statements: (bankAccountId?: string, page = 0) => api.get<Page<StatementSummary>>(`/bank-statements${qs({ bankAccountId, page, size: 25 })}`),
  statement: (id: string) => api.get<{ summary: StatementSummary; lines: StatementLine[]; warnings: string[] }>(`/bank-statements/${id}`),
  importStatement: (file: File, fields: { bankAccountId: string; format: string; openingBalance?: string; closingBalance?: string }) =>
    api.uploadWith<{ summary: StatementSummary }>('/bank-statements', file,
      Object.fromEntries(Object.entries(fields).filter(([, v]) => v !== undefined && v !== '')) as Record<string, string>),
  startReconciliation: (statementId: string) => api.post<ReconciliationView>(`/bank-statements/${statementId}/reconciliation`),
  reconciliation: (id: string) => api.get<ReconciliationView>(`/bank-reconciliations/${id}`),
  reconciliations: (page = 0, size = 25) => api.get<Page<{ id: string; bankAccountCode: string; periodStart: string; asOfDate: string; status: string }>>(
    `/bank-reconciliations${qs({ page, size })}`),
  autoMatch: (id: string, version: number) =>
    api.post<{ matched: number; reconciliation: ReconciliationView }>(`/bank-reconciliations/${id}/auto-match`, { version }),
  match: (id: string, version: number, statementLineIds: string[], journalLineIds: string[]) =>
    api.post<ReconciliationView>(`/bank-reconciliations/${id}/matches`, { version, statementLineIds, journalLineIds }),
  unmatch: (id: string, version: number, matchId: string) =>
    api.delete<ReconciliationView>(`/bank-reconciliations/${id}/matches/${matchId}?version=${version}`),
  act: (id: string, action: 'submit' | 'approve' | 'return', version: number, comment?: string) =>
    api.post<ReconciliationView>(`/bank-reconciliations/${id}/${action}`, { version, comment: comment || null }),
};

// ---------------------------------------------------------------- Phase 3d

export const assetApi = {
  categories: () => api.get<AssetCategory[]>('/asset-categories'),
  createCategory: (body: { code: string; name: string; assetAccountId: string; accumulatedAccountId: string; expenseAccountId: string;
    defaultUsefulLifeMonths: number; defaultResidualRate: string | null }) => api.post<AssetCategory>('/asset-categories', body),
  projects: () => api.get<AucProject[]>('/auc-projects'),
  createProject: (body: { code: string; name: string; aucAccountId: string; costCenterId: string | null }) =>
    api.post<AucProject>('/auc-projects', body),
  closeProject: (p: AucProject) => api.post<AucProject>(`/auc-projects/${p.id}/close`, { version: p.version }),
  list: (q: { status?: string; categoryId?: string; q?: string; page?: number }) => api.get<Page<AssetSummary>>(`/fixed-assets${qs({ ...q, size: 50 })}`),
  get: (id: string) => api.get<AssetView>(`/fixed-assets/${id}`),
  create: (body: AssetInput) => api.post<AssetView>('/fixed-assets', body),
  activate: (id: string, version: number) => api.post<AssetView>(`/fixed-assets/${id}/activate`, { version }),
  cancel: (id: string, version: number, reason: string) => api.post<AssetView>(`/fixed-assets/${id}/cancel`, { version, reason }),
  runs: (page = 0) => api.get<Page<DepreciationRunSummary>>(`/depreciation-runs${qs({ page, size: 25 })}`),
  run: (id: string) => api.get<DepreciationRun>(`/depreciation-runs/${id}`),
  prepareRun: (periodId: string) => api.post<DepreciationRun>('/depreciation-runs', { periodId }),
  runAction: (id: string, action: 'post' | 'cancel', version: number) =>
    api.post<DepreciationRun>(`/depreciation-runs/${id}/${action}`, { version }),
  prepareDisposal: (body: { assetId: string; disposalDate: string; method: string; proceeds: string; proceedsAccountId: string | null;
    reason: string }) => api.post<DisposalView>('/asset-disposals', body),
  transfers: (assetId: string) => api.get<AssetTransfer[]>(`/fixed-assets/${assetId}/transfers`),
  prepareTransfer: (assetId: string, body: { transferDate: string; toCostCenterId: string | null; toLocation: string | null;
    toCustodian: string | null; reason: string }) => api.post<AssetTransfer>(`/fixed-assets/${assetId}/transfers`, body),
  transferAction: (t: AssetTransfer, action: 'post' | 'cancel') => api.post<AssetTransfer>(`/asset-transfers/${t.id}/${action}`, { version: t.version }),
  depreciationSettings: () => api.get<{ frequency: 'MONTHLY' | 'ANNUAL' }>('/depreciation-settings'),
  disposalAction: (id: string, action: 'post' | 'cancel', version: number) =>
    api.post<DisposalView>(`/asset-disposals/${id}/${action}`, { version }),
  schedule: (from: string, to: string) =>
    api.get<{ from: string; to: string; rows: PpeRow[]; totals: PpeRow; note: string }>(`/fixed-assets/reports/ppe-schedule${qs({ from, to })}`),
  reconciliation: () => api.get<{ rows: { accountCode: string; accountName: string; side: string; register: string; generalLedger: string;
    difference: string }[]; reconciled: boolean }>('/fixed-assets/reports/reconciliation'),
};

// ---------------------------------------------------------------- Phase 3e

export const inventoryApi = {
  warehouses: () => api.get<Warehouse[]>('/inventory/warehouses'),
  createWarehouse: (code: string, name: string) => api.post<Warehouse>('/inventory/warehouses', { code, name }),
  items: (q?: string, page = 0, size = 50) => api.get<Page<InventoryItem>>(`/inventory/items${qs({ q: q || undefined, page, size })}`),
  item: (id: string) => api.get<InventoryItem>(`/inventory/items/${id}`),
  createItem: (body: { code: string; name: string; unitOfMeasure: string; inventoryAccountId: string; consumptionAccountId: string }) =>
    api.post<InventoryItem>('/inventory/items', body),
  movements: (id: string, page = 0) => api.get<Page<InventoryMovement>>(`/inventory/items/${id}/movements${qs({ page, size: 100 })}`),
  importDocument: (file: File, fields: { kind: string; documentDate: string; description: string; supplierId?: string; costCenterId?: string;
    reference?: string }) => api.uploadWith<InventoryDocument>('/inventory/documents/import', file,
    Object.fromEntries(Object.entries(fields).filter(([, v]) => v)) as Record<string, string>),
  documents: (kind?: string, status?: string, page = 0) =>
    api.get<Page<InventoryDocumentSummary>>(`/inventory/documents${qs({ kind, status, page, size: 25 })}`),
  document: (id: string) => api.get<InventoryDocument>(`/inventory/documents/${id}`),
  createDocument: (body: { kind: InventoryKind; documentDate: string; supplierId: string | null; reference: string | null;
    costCenterId: string | null; description: string; lines: InventoryLineInput[] }) => api.post<InventoryDocument>('/inventory/documents', body),
  act: (id: string, action: 'post' | 'cancel', version: number) => api.post<InventoryDocument>(`/inventory/documents/${id}/${action}`, { version }),
  grIr: () => api.get<GrIrStatus>('/inventory/gr-ir'),
  match: (grnId: string, invoiceId: string, amount: string) => api.post<GrIrStatus>('/inventory/gr-ir/matches', { grnId, invoiceId, amount }),
  reconciliation: () => api.get<{ rows: { accountCode: string; accountName: string; side: string; register: string; generalLedger: string;
    difference: string }[]; reconciled: boolean }>('/inventory/reconciliation'),
};

// ---------------------------------------------------------------- Phase 4a

export const reportingApi = {
  templates: () => api.get<StatementTemplate[]>('/report-templates'),
  activate: (id: string) => api.post<StatementTemplate>(`/report-templates/${id}/activate`),
  statement: (template: string, periodId: string, comparative: boolean) =>
    api.get<StatementResult>(`/financial-statements${qs({ template, periodId, comparative: String(comparative) })}`),
  requestJob: (reportType: string, format: string, parameters: Record<string, string>) =>
    api.post<ReportJob>('/report-jobs', { reportType, format, parameters }),
  jobs: (page = 0) => api.get<Page<ReportJob>>(`/report-jobs${qs({ page, size: 25 })}`),
  download: (job: ReportJob) => downloadFile(`/report-jobs/${job.id}/file`, job.fileName ?? 'report'),
  schedules: () => api.get<ReportSchedule[]>('/report-schedules'),
  createSchedule: (body: { name: string; reportType: string; format: string; parameters: Record<string, string>; frequency: string;
    runHour: number; dayOfWeek?: number; dayOfMonth?: number; emailWhenReady?: boolean }) => api.post<ReportSchedule>('/report-schedules', body),
  setScheduleEmail: (s: ReportSchedule, emailWhenReady: boolean) =>
    api.put<ReportSchedule>(`/report-schedules/${s.id}/email`, { emailWhenReady, version: s.version }),
  deleteSchedule: (id: string) => api.delete<ReportSchedule>(`/report-schedules/${id}`),
};

export const dashboardApi = {
  mine: () => api.get<Dashboard>('/dashboard'),
};

export const inboxApi = {
  mine: (q?: string) => api.get<Inbox>(`/inbox${qs({ q: q || undefined })}`),
};

export const notificationApi = {
  list: (unreadOnly: boolean, page = 0, size = 10) => api.get<Page<AppNotification>>(`/notifications${qs({ unreadOnly: String(unreadOnly), page, size })}`),
  unreadCount: () => api.get<{ unread: number }>('/notifications/unread-count'),
  markRead: (id: string) => api.post<void>(`/notifications/${id}/read`),
  markAllRead: () => api.post<{ unread: number }>('/notifications/read-all'),
};

// ---------------------------------------------------------------- Phase 4c

export const integrationApi = {
  channels: () => api.get<IntegrationChannel[]>('/integrations/channels'),
  transmissions: (channel?: string, status?: string, page = 0) =>
    api.get<Page<Transmission>>(`/integrations/transmissions${qs({ channel, status, page, size: 50 })}`),
  transmission: (id: string) => api.get<Transmission>(`/integrations/transmissions/${id}`),
  retry: (id: string) => api.post<Transmission>(`/integrations/transmissions/${id}/retry`),
  cancel: (id: string, reason: string) => api.post<Transmission>(`/integrations/transmissions/${id}/cancel`, { reason }),
  queuePosted: (from: string, to: string) => api.post<{ queued: number }>('/integrations/bir-eis/posted', { from, to }),
};

export const supplierBankApi = {
  list: (supplierId: string) => api.get<SupplierBankAccount[]>(`/suppliers/${supplierId}/bank-accounts`),
  add: (supplierId: string, body: { bankName: string; branch: string | null; accountName: string; accountNumber: string }) =>
    api.post<SupplierBankAccount>(`/suppliers/${supplierId}/bank-accounts`, body),
  verify: (id: string, approve: boolean, note: string) =>
    api.post<SupplierBankAccount>(`/supplier-bank-accounts/${id}/verification`, { approve, note }),
};

export const paymentFileApi = {
  eligible: () => api.get<EligibleDv[]>('/payment-files/eligible'),
  list: (page = 0) => api.get<Page<PaymentBatch>>(`/payment-files${qs({ page, size: 25 })}`),
  create: (bankAccountId: string, paymentDate: string, dvIds: string[], idempotencyKey: string) =>
    api.post<PaymentBatch>('/payment-files', { bankAccountId, paymentDate, dvIds }, { headers: { 'Idempotency-Key': idempotencyKey } }),
  download: (b: PaymentBatch) => downloadFile(`/payment-files/${b.id}/file`, b.fileName),
};

// ---------------------------------------------------------------- Phase 4d

export type TicketActionExtra = { comment?: string; internal?: boolean; assignee?: string; priority?: string; severity?: string;
  categoryId?: string; rollbackPlan?: string; releaseNotes?: string; releaseReference?: string; satisfaction?: number };

export const ticketApi = {
  list: (all: boolean, status?: string, type?: string, page = 0, severity?: string, categoryId?: string) =>
    api.get<Page<TicketSummary>>(`/tickets${qs({ all: String(all), status, type, severity, categoryId, page, size: 25 })}`),
  get: (id: string) => api.get<TicketView>(`/tickets/${id}`),
  create: (body: { ticketType: string; priority: string; severity: string; categoryId: string; title: string; description: string;
    module: string | null }) => api.post<TicketView>('/tickets', body),
  act: (t: TicketView, action: string, extra: TicketActionExtra = {}) =>
    api.post<TicketView>(`/tickets/${t.id}/actions`, { version: t.version, action, ...extra }),
  attach: (id: string, file: File) => api.upload<TicketView>(`/tickets/${id}/documents`, file),
  removeDocument: (documentId: string) => api.post<DocumentInfo>(`/documents/${documentId}/remove`),
  categories: () => api.get<TicketCategory[]>('/ticket-categories'),
  createCategory: (body: { code: string; name: string; sortOrder: number }) => api.post<TicketCategory>('/ticket-categories', body),
  updateCategory: (id: string, body: { name: string; sortOrder: number; status: 'ACTIVE' | 'INACTIVE' }) =>
    api.put<TicketCategory>(`/ticket-categories/${id}`, body),
  summary: (from: string, to: string) => api.get<Record<string, string | number>[]>(`/tickets/sla-summary${qs({ from, to })}`),
  policies: () => api.get<SlaPolicy[]>('/sla-policies'),
  createPolicy: (body: { contractPhase: string; ticketType: string; priority: string; responseMinutes: number; resolutionMinutes: number;
    businessHours: boolean }) => api.post<SlaPolicy>('/sla-policies', body),
  calendar: () => api.get<BusinessCalendar>('/business-calendar'),
  addHoliday: (date: string, name: string) => api.post<BusinessCalendar>('/business-calendar/holidays', { date, name }),
  removeHoliday: (date: string) => api.delete<BusinessCalendar>(`/business-calendar/holidays/${date}`),
};

// ---------------------------------------------------------------- Phase 4e

export const portalAccessApi = {
  list: (q: { customerId?: string; status?: string; page?: number }) => api.get<Page<PortalLinkRequest>>(`/portal-link-requests${qs({ ...q, size: 25 })}`),
  request: (customerId: string, username: string, evidence: string) =>
    api.post<PortalLinkRequest>(`/customers/${customerId}/portal-link-requests`, { username, evidence }),
  decide: (id: string, approve: boolean, note: string) => api.post<PortalLinkRequest>(`/portal-link-requests/${id}/decision`, { approve, note }),
  cancel: (id: string) => api.post<PortalLinkRequest>(`/portal-link-requests/${id}/cancel`),
  upload: (id: string, file: File) => api.upload<DocumentInfo>(`/portal-link-requests/${id}/documents`, file),
};

export const migrationApi = {
  list: (kind?: string, page = 0) => api.get<Page<MigrationBatchSummary>>(`/migration/batches${qs({ kind, page, size: 25 })}`),
  get: (id: string) => api.get<MigrationBatchDetail>(`/migration/batches/${id}`),
  upload: (file: File, fields: { kind: MigrationKind; description: string; cutoverDate?: string; openingEntryNumber?: string }) =>
    api.uploadWith<MigrationBatchDetail>('/migration/batches', file, Object.fromEntries(Object.entries(fields)
      .filter(([, v]) => v !== undefined && v !== '')) as Record<string, string>),
  apply: (b: MigrationBatchSummary) => api.post<MigrationBatchDetail>(`/migration/batches/${b.id}/apply`, { version: b.version }),
  cancel: (b: MigrationBatchSummary, reason: string) => api.post<MigrationBatchDetail>(`/migration/batches/${b.id}/cancel`, { version: b.version, reason }),
  attach: (id: string, file: File) => api.upload<DocumentInfo>(`/migration/batches/${id}/documents`, file),
  template: (kind: MigrationKind) => downloadFile(`/migration/templates/${kind}`, `${kind.toLowerCase()}-template.csv`),
};

export const parallelRunApi = {
  list: (page = 0) => api.get<Page<ParallelRunSummary>>(`/parallel-runs${qs({ page, size: 25 })}`),
  get: (id: string) => api.get<ParallelRunDetail>(`/parallel-runs/${id}`),
  upload: (file: File, fields: { periodId: string; basis: string; legacySystem: string }) =>
    api.uploadWith<ParallelRunDetail>('/parallel-runs', file, fields),
  recompare: (s: ParallelRunSummary) => api.post<ParallelRunDetail>(`/parallel-runs/${s.id}/recompare`, { version: s.version }),
  explain: (id: string, lineId: string, explanation: string) =>
    api.post<ParallelRunDetail>(`/parallel-runs/${id}/lines/${lineId}/explanation`, { explanation }),
  signOff: (s: ParallelRunSummary, note: string) => api.post<ParallelRunDetail>(`/parallel-runs/${s.id}/sign-off`, { version: s.version, note }),
  cancel: (s: ParallelRunSummary, reason: string) => api.post<ParallelRunDetail>(`/parallel-runs/${s.id}/cancel`, { version: s.version, reason }),
  template: () => downloadFile('/parallel-runs/template', 'legacy-trial-balance-template.csv'),
};

// ---------------------------------------------------------------- Access governance (gap 7)

export const governanceApi = {
  grants: (status?: string, page = 0) => api.get<Page<RoleGrant>>(`/role-grants${qs({ status, page, size: 25 })}`),
  decideGrant: (g: RoleGrant, action: 'approve' | 'reject', note: string) =>
    api.post<RoleGrant>(`/role-grants/${g.id}/${action}`, { version: g.version, note: note || null }),
  cancelGrant: (g: RoleGrant) => api.post<RoleGrant>(`/role-grants/${g.id}/cancel`, { version: g.version }),
  cycles: () => api.get<AccessReviewCycle[]>('/access-review-cycles'),
  startCycle: (name: string, dueDate: string) => api.post<AccessReviewCycle>('/access-review-cycles', { name, dueDate }),
  cycle: (id: string) => api.get<AccessReviewCycleDetail>(`/access-review-cycles/${id}`),
  closeCycle: (c: AccessReviewCycle) => api.post<AccessReviewCycle>(`/access-review-cycles/${c.id}/close`, { version: c.version }),
  review: (i: AccessReviewItem, outcome: string, notes: string) =>
    api.post<AccessReviewItem>(`/access-reviews/${i.id}/review`, { version: i.version, outcome, notes: notes || null }),
  recordAction: (i: AccessReviewItem, note: string) => api.post<AccessReviewItem>(`/access-reviews/${i.id}/action`, { version: i.version, note: note || null }),
  limits: () => api.get<{ limits: ApprovalLimit[]; documentTypes: string[] }>('/approval-limits'),
  setLimit: (roleCode: string, documentType: string, maxAmount: string) =>
    api.put<ApprovalLimit>(`/approval-limits/${roleCode}/${documentType}`, { maxAmount }),
  removeLimit: (roleCode: string, documentType: string) => api.delete<void>(`/approval-limits/${roleCode}/${documentType}`),
};

// ---------------------------------------------------------------- Organisation (gap 8)

export const orgApi = {
  tree: () => api.get<Organization[]>('/organizations'),
  createOffice: (orgId: string, code: string, name: string) => api.post<{ id: string }>(`/organizations/${orgId}/offices`, { code, name }),
  updateOffice: (o: { id: string; version: number }, name: string, status: string) =>
    api.put<Organization[]>(`/offices/${o.id}`, { version: o.version, name, status }),
  createDepartment: (officeId: string, code: string, name: string, costCenterId: string | null) =>
    api.post<{ id: string }>(`/offices/${officeId}/departments`, { code, name, costCenterId }),
  updateDepartment: (d: { id: string; version: number }, name: string, status: string, costCenterId: string | null) =>
    api.put<Organization[]>(`/departments/${d.id}`, { version: d.version, name, status, costCenterId }),
  assignments: (userId: string) => api.get<OfficeAssignment[]>(`/users/${userId}/office-assignments`),
  assign: (userId: string, officeId: string, departmentId: string | null, effectiveFrom: string) =>
    api.post<OfficeAssignment[]>(`/users/${userId}/office-assignments`, { officeId, departmentId, effectiveFrom }),
  end: (assignmentId: string, effectiveTo: string) => api.post<OfficeAssignment[]>(`/office-assignments/${assignmentId}/end`, { effectiveTo }),
};
