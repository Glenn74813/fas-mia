// Typed mirrors of the backend DTOs (backend is the source of truth; see /v3/api-docs).

/** DEV_MFA_BYPASS is returned only by a development server, for users configured to skip the authenticator code. */
export type LoginStatus = 'MFA_REQUIRED' | 'MFA_ENROLLMENT_REQUIRED' | 'DEV_MFA_BYPASS';

export interface LoginResponse {
  status: LoginStatus;
  mfaToken: string;
  /** Second factors this sign-in step accepts: always TOTP; EMAIL when codes by e-mail are enabled. */
  methods?: string[];
}

export interface TotpEnrollment {
  secret: string;
  otpauthUri: string;
}

export interface CurrentUser {
  id: string;
  username: string;
  displayName: string;
  email: string;
  userType: 'INTERNAL' | 'CONCESSIONAIRE' | 'EXTERNAL_AUDITOR';
  roles: string[];
  permissions: string[];
  mustChangePassword: boolean;
  mfaEnrolled: boolean;
  recoveryCodesRemaining: number;
  lastLoginAt: string | null;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  sessionExpiresAt: string;
  user: CurrentUser;
  /** Present only right after an authenticator is enrolled: the one time the codes are shown. */
  recoveryCodes?: string[];
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface UserSummary {
  id: string;
  username: string;
  displayName: string;
  email: string;
  userType: string;
  status: 'ACTIVE' | 'INACTIVE';
  locked: boolean;
  lastLoginAt: string | null;
  version: number;
}

export interface UserDetail extends Omit<UserSummary, 'lastLoginAt'> {
  lockedUntil: string | null;
  mustChangePassword: boolean;
  mfaEnrolled: boolean;
  accessExpiresAt: string | null;
  lastLoginAt: string | null;
  passwordChangedAt: string;
  createdAt: string;
  createdBy: string;
  roles: string[];
  effectivePermissions: string[];
  activeSessions: number;
}

export interface SodConflict {
  permissionA: string;
  permissionB: string;
  severity: 'BLOCK' | 'WARN';
  rationale: string;
}

export interface RoleAssignmentResult {
  user: UserDetail;
  warnings: SodConflict[];
  /** Set when financial roles wait for a second person's approval instead of applying (C-19). */
  pendingGrant: RoleGrant | null;
}

export interface Role {
  id: string;
  code: string;
  name: string;
  description: string | null;
  systemRole: boolean;
  permissionCodes: string[];
  userCount: number;
  version: number;
}

export interface RoleChangeResult {
  role: Role;
  warnings: SodConflict[];
}

export interface Permission {
  code: string;
  description: string;
  category: string;
  financial: boolean;
}

export interface EventRecord {
  sequence: number;
  eventId: string;
  occurredAt: string;
  actorUserId: string | null;
  actorUsername: string | null;
  /** Comma-separated role codes at the time of the event; null for system events and events before V34. */
  actorRoles: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  outcome: 'SUCCESS' | 'FAILURE' | 'DENIED';
  severity: string | null;
  correlationId: string | null;
  clientIp: string | null;
  details: Record<string, unknown>;
  rowHash: string;
}

export interface ChainVerification {
  table: string;
  intact: boolean;
  rowsChecked: number;
  firstInvalidSequence: number | null;
  verifiedAt: string;
}

// ---------------------------------------------------------------- Phase 2: accounting

export interface Account {
  id: string;
  code: string;
  name: string;
  description: string | null;
  accountType: 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE';
  normalBalance: 'DEBIT' | 'CREDIT';
  parentId: string | null;
  parentCode: string | null;
  postable: boolean;
  status: 'ACTIVE' | 'INACTIVE';
  effectiveFrom: string;
  effectiveTo: string | null;
  fsLineCode: string | null;
  requiresCostCenter: boolean;
  subsidiaryType: string | null;
  budgetControlled: boolean;
  taxCode: string | null;
  placeholder: boolean;
  version: number;
}

export interface ChartSummary {
  accounts: Account[];
  activePlaceholderAccounts: number;
}

export interface CostCenter {
  id: string;
  code: string;
  name: string;
  status: string;
  placeholder: boolean;
  version: number;
}

export interface SubsidiaryAccount {
  id: string;
  slType: string;
  code: string;
  name: string;
  status: string;
  version: number;
}

export interface Period {
  id: string;
  fiscalYearId: string;
  fiscalYear: number;
  periodNumber: number;
  name: string;
  periodType: 'REGULAR' | 'ADJUSTMENT';
  startDate: string;
  endDate: string;
  status: 'FUTURE' | 'OPEN' | 'SOFT_CLOSED' | 'CLOSED';
  version: number;
}

export interface FiscalYear {
  id: string;
  fiscalYear: number;
  startDate: string;
  endDate: string;
  status: 'OPEN' | 'CLOSED';
  closedAt: string | null;
  closedBy: string | null;
  periods: Period[];
  version: number;
}

export interface CloseCheck {
  code: string;
  description: string;
  outcome: 'PASS' | 'FAIL' | 'NOT_AVAILABLE';
  waivable: boolean;
  detail: string;
}

export interface CloseChecklist {
  period: Period;
  checks: CloseCheck[];
  readyToClose: boolean;
}

export interface PeriodChange {
  id: string;
  periodId: string;
  periodName: string;
  changeType: string;
  fromStatus: string;
  toStatus: string;
  requestStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | 'APPLIED';
  reason: string | null;
  waivedChecks: string[];
  requestedBy: string;
  requestedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
}

export type VoucherStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'POSTED' | 'RETURNED' | 'REJECTED' | 'CANCELLED';

export interface VoucherSummary {
  id: string;
  voucherNumber: string | null;
  voucherType: string;
  status: VoucherStatus;
  accountingDate: string;
  periodName: string;
  description: string;
  totalDebit: string;
  totalCredit: string;
  preparedBy: string;
  currentStep: string | null;
  updatedAt: string;
  version: number;
}

export interface VoucherLine {
  id: string;
  lineNo: number;
  accountId: string;
  accountCode: string;
  accountName: string;
  placeholderAccount: boolean;
  costCenterId: string | null;
  costCenterCode: string | null;
  subsidiaryAccountId: string | null;
  subsidiaryCode: string | null;
  subsidiaryName: string | null;
  description: string | null;
  debit: string;
  credit: string;
}

export interface StepState {
  order: number;
  code: string;
  permission: string;
  state: 'DONE' | 'CURRENT' | 'PENDING';
  actor: string | null;
  decidedAt: string | null;
}

export interface Decision {
  id: string;
  action: string;
  stepOrder: number | null;
  stepCode: string | null;
  actorUsername: string;
  comment: string | null;
  decidedAt: string;
}

export interface DocumentInfo {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  scanStatus: string;
  uploadedBy: string;
  uploadedAt: string;
  removedAt: string | null;
  removedBy: string | null;
}

export type VoucherAction = 'EDIT' | 'CANCEL' | 'SUBMIT' | 'UPLOAD' | 'APPROVE' | 'RETURN' | 'REJECT' | 'POST' | 'REVERSE';

export interface VoucherDetail {
  id: string;
  voucherNumber: string | null;
  voucherType: string;
  status: VoucherStatus;
  accountingDate: string;
  periodId: string;
  periodName: string;
  periodStatus: string;
  description: string;
  supportingReference: string | null;
  sourceType: string;
  sourceReference: string | null;
  currencyCode: string;
  totalDebit: string;
  totalCredit: string;
  balanced: boolean;
  preparedBy: string;
  preparedById: string;
  submittedAt: string | null;
  postedEntryId: string | null;
  reversalOfEntryId: string | null;
  reversalOfEntryNumber: string | null;
  requiresAttachment: boolean;
  lines: VoucherLine[];
  workflow: StepState[];
  decisions: Decision[];
  documents: DocumentInfo[];
  availableActions: VoucherAction[];
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface LineInput {
  accountId: string;
  costCenterId: string | null;
  subsidiaryAccountId: string | null;
  description: string | null;
  debit: string;
  credit: string;
}

export interface VoucherInput {
  voucherType: 'STANDARD' | 'ADJUSTING' | 'OPENING';
  accountingDate: string;
  adjustmentPeriod: boolean;
  description: string;
  supportingReference: string | null;
  lines: LineInput[];
}

export interface TrialBalanceRow {
  accountId: string | null;
  code: string;
  name: string;
  accountType: string;
  normalBalance: string;
  placeholder: boolean;
  openingDebit: string;
  openingCredit: string;
  periodDebit: string;
  periodCredit: string;
  closingDebit: string;
  closingCredit: string;
}

export interface TrialBalance {
  period: Period;
  rows: TrialBalanceRow[];
  totalOpeningDebit: string;
  totalOpeningCredit: string;
  totalPeriodDebit: string;
  totalPeriodCredit: string;
  totalClosingDebit: string;
  totalClosingCredit: string;
  balanced: boolean;
  placeholderAccountsUsed: boolean;
  source: string;
}

export interface LedgerLine {
  lineId: string;
  entryId: string;
  entryNumber: string;
  voucherId: string;
  accountingDate: string;
  periodName: string;
  description: string;
  costCenterCode: string | null;
  subsidiaryCode: string | null;
  debit: string;
  credit: string;
  runningBalance: string;
}

export interface AccountLedger {
  account: Account;
  from: string;
  to: string;
  openingBalance: string;
  periodDebit: string;
  periodCredit: string;
  closingBalance: string;
  balanceSign: string;
  lines: LedgerLine[];
  page: number;
  size: number;
  totalLines: number;
}

export interface EntryLine {
  lineNo: number;
  accountId: string;
  accountCode: string;
  accountName: string;
  costCenterCode: string | null;
  subsidiaryCode: string | null;
  subsidiaryName: string | null;
  description: string | null;
  debit: string;
  credit: string;
}

export interface EntryDetail {
  id: string;
  entryNumber: string;
  voucherId: string;
  voucherType: string;
  accountingDate: string;
  periodName: string;
  description: string;
  sourceType: string;
  sourceReference: string | null;
  totalDebit: string;
  totalCredit: string;
  reversalOfEntryId: string | null;
  reversalOfEntryNumber: string | null;
  reversedByEntryId: string | null;
  reversedByEntryNumber: string | null;
  preparedBy: string;
  postedBy: string;
  postedAt: string;
  lines: EntryLine[];
}

export interface RecurringJournal {
  id: string;
  name: string;
  description: string;
  frequency: string;
  dayOfMonth: number;
  nextRunDate: string;
  endDate: string | null;
  status: string;
  owner: string;
  total: string;
  version: number;
}

// ---------------------------------------------------------------- Phase 3a: payables, budgets, settings

export interface Supplier {
  id: string;
  code: string;
  name: string;
  tin: string | null;
  address: string | null;
  vatRegistered: boolean;
  defaultEwtCodeId: string | null;
  defaultEwtCode: string | null;
  subsidiaryAccountId: string;
  status: string;
  openBalance: string;
  version: number;
}

export interface TaxCode {
  id: string;
  code: string;
  description: string;
  taxType: 'EWT' | 'VAT_INPUT' | 'VAT_OUTPUT' | 'FINAL_VAT';
  rate: string;
  accountId: string;
  accountCode: string;
  status: string;
  placeholder: boolean;
}

export interface BankAccount {
  id: string;
  code: string;
  bankName: string;
  accountName: string;
  accountNumberLast4: string;
  glAccountId: string;
  glAccountCode: string;
  status: string;
}

export interface AccountMapping {
  key: string;
  description: string;
  accountId: string | null;
  accountCode: string | null;
  accountName: string | null;
}

export interface InvoiceLineInput {
  accountId: string;
  costCenterId: string | null;
  description: string | null;
  netAmount: string;
  ewtCodeId: string | null;
}

export interface InvoiceInput {
  supplierId: string;
  invoiceNumber: string;
  invoiceDate: string;
  dueDate: string;
  accountingDate: string;
  description: string;
  vatCodeId: string | null;
  lines: InvoiceLineInput[];
}

export interface InvoiceSummary {
  id: string;
  supplierCode: string;
  supplierName: string;
  invoiceNumber: string;
  invoiceDate: string;
  dueDate: string;
  status: string;
  payableAmount: string;
  paidAmount: string;
  openBalance: string;
  preparedBy: string;
  updatedAt: string;
  version: number;
}

export interface InvoiceView {
  id: string;
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  supplierTin: string | null;
  invoiceNumber: string;
  invoiceDate: string;
  dueDate: string;
  accountingDate: string;
  periodName: string;
  description: string;
  status: string;
  currentStep: string | null;
  vatCodeId: string | null;
  vatCode: string | null;
  netAmount: string;
  vatAmount: string;
  ewtAmount: string;
  payableAmount: string;
  paidAmount: string;
  openBalance: string;
  preparedBy: string;
  postedEntryId: string | null;
  postedEntryNumber: string | null;
  lines: { lineNo: number; accountId: string; accountCode: string; accountName: string; costCenterId: string | null;
    costCenterCode: string | null; description: string | null; netAmount: string; ewtCodeId: string | null;
    ewtCode: string | null; ewtAmount: string }[];
  decisions: Decision[];
  documents: DocumentInfo[];
  possibleDuplicates: string[];
  availableActions: string[];
  version: number;
}

export interface DvSummary {
  id: string;
  dvNumber: string | null;
  supplierName: string;
  particulars: string;
  status: string;
  currentStep: string | null;
  amount: string;
  preparedBy: string;
  updatedAt: string;
  version: number;
}

export interface DvView {
  id: string;
  dvNumber: string | null;
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  particulars: string;
  status: string;
  currentStep: string | null;
  amount: string;
  paymentMode: string | null;
  bankAccountId: string | null;
  bankAccountCode: string | null;
  paymentReference: string | null;
  paymentDate: string | null;
  instructedBy: string | null;
  instructedAt: string | null;
  confirmedBy: string | null;
  confirmedAt: string | null;
  preparedBy: string;
  postedEntryId: string | null;
  postedEntryNumber: string | null;
  lines: { invoiceId: string; invoiceNumber: string; dueDate: string; invoicePayable: string; amount: string }[];
  /** The COB position of each budget line this DV pays (for the Budget Division's certification). */
  budget: DvBudgetLine[];
  check: DvCheck | null;
  decisions: Decision[];
  documents: DocumentInfo[];
  availableActions: string[];
  version: number;
}

export interface DvBudgetLine {
  accountCode: string;
  accountName: string;
  costCenterCode: string | null;
  charged: string;
  status: 'ALLOCATED' | 'NO_ALLOCATION' | 'NO_BUDGET';
  allocation: string | null;
  allotment: string | null;
  used: string | null;
  available: string | null;
}

export interface DvCheck {
  bankAccountCode: string;
  checkNumber: string;
  status: 'PREPARED' | 'ISSUED' | 'RELEASED' | 'VOIDED' | 'CLEARED';
  preparedBy: string;
  approvedBy: string | null;
  approvedAt: string | null;
}

export interface AgingRow {
  supplierId: string | null;
  supplierCode: string | null;
  supplierName: string;
  current: string;
  days1to30: string;
  days31to60: string;
  days61to90: string;
  over90: string;
  total: string;
}

export interface AllocationLine {
  id: string;
  accountId: string;
  accountCode: string;
  accountName: string;
  costCenterId: string | null;
  costCenterCode: string | null;
  allotment: string;
  reserved: string;
  actual: string;
  available: string;
  utilizationPercent: string;
}

export interface BudgetView {
  id: string;
  fiscalYearId: string;
  fiscalYear: number;
  name: string;
  status: string;
  currentStep: string | null;
  preparedBy: string;
  approvedAt: string | null;
  approvedBy: string | null;
  allocations: AllocationLine[];
  totalAllotment: string;
  totalReserved: string;
  totalActual: string;
  totalAvailable: string;
  availableActions: string[];
  version: number;
}

export interface BudgetAdjustment {
  id: string;
  fromAllocationId: string | null;
  fromLabel: string;
  toAllocationId: string;
  toLabel: string;
  amount: string;
  reason: string;
  status: string;
  requestedBy: string;
  requestedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
}

// ---------------------------------------------------------------- Phase 3b: receivables

export interface Customer {
  id: string; code: string; name: string; customerType: string; tin: string | null; address: string | null;
  email: string | null; creditLimit: string | null; paymentTermsDays: number; subsidiaryAccountId: string; status: string;
  openBalance: string; unappliedCollections: string; version: number;
}

export interface CustomerInput {
  code: string; name: string; customerType: string; tin: string | null; address: string | null; email: string | null;
  creditLimit: string | null; paymentTermsDays: number;
}

export interface Contract {
  id: string; customerId: string; contractNumber: string; description: string; startDate: string; endDate: string | null;
  monthlyAmount: string; revenueAccountId: string; revenueAccountCode: string; costCenterId: string | null;
  costCenterCode: string | null; vatCodeId: string | null; vatCode: string | null; status: string; version: number;
}

export interface PortalUser { userId: string; username: string; displayName: string; status: string }

export type BillingKind = 'INVOICE' | 'CREDIT_MEMO' | 'DEBIT_MEMO';

export interface BillingLineInput { revenueAccountId: string; costCenterId: string | null; description: string; netAmount: string }

export interface BillingInput {
  kind: BillingKind; customerId: string; relatedInvoiceId: string | null; documentDate: string; dueDate: string | null;
  description: string; vatCodeId: string | null; lines: BillingLineInput[];
}

export interface BillingSummary {
  id: string; kind: BillingKind; documentNumber: string | null; customerCode: string; customerName: string;
  documentDate: string; dueDate: string; billingPeriod: string | null; status: string; grossAmount: string;
  settledAmount: string; openBalance: string; preparedBy: string; updatedAt: string; version: number;
}

export interface BillingLine {
  lineNo: number; revenueAccountId: string; accountCode: string; accountName: string; costCenterId: string | null;
  costCenterCode: string | null; description: string; netAmount: string;
}

export interface BillingView {
  id: string; kind: BillingKind; documentNumber: string | null; customerId: string; customerCode: string; customerName: string;
  customerTin: string | null; contractId: string | null; contractNumber: string | null; billingPeriod: string | null;
  relatedInvoiceId: string | null; relatedInvoiceNumber: string | null; documentDate: string; dueDate: string;
  periodName: string; description: string; status: string; currentStep: string | null; vatCodeId: string | null;
  vatCode: string | null; netAmount: string; vatAmount: string; grossAmount: string; settledAmount: string;
  openBalance: string; source: string; preparedBy: string; postedEntryId: string | null; postedEntryNumber: string | null;
  lines: BillingLine[]; decisions: Decision[]; documents: DocumentInfo[]; warnings: string[]; availableActions: string[];
  version: number;
}

export interface OpenItem {
  id: string; kind: BillingKind; documentNumber: string; documentDate: string; dueDate: string; grossAmount: string;
  openBalance: string; pendingCollections: string; available: string;
}

export interface ReceiptSummary {
  id: string; receiptNumber: string; customerCode: string; customerName: string; receiptDate: string; paymentMethod: string;
  amount: string; taxWithheld: string; unappliedAmount: string; status: string; recordedBy: string; updatedAt: string;
  version: number;
}

export interface ReceiptAllocation {
  documentId: string; documentNumber: string; kind: BillingKind; amount: string; appliedLater: boolean;
  postedEntryNumber: string | null; createdAt: string;
}

export interface ReceiptView {
  id: string; receiptNumber: string; customerId: string; customerCode: string; customerName: string; receiptDate: string;
  paymentMethod: string; paymentReference: string | null; amount: string; taxWithheld: string; allocatedAmount: string;
  unappliedAmount: string; status: string; remarks: string | null; recordedBy: string; cancelReason: string | null;
  postedEntryId: string | null; postedEntryNumber: string | null; allocations: ReceiptAllocation[];
  documents: DocumentInfo[]; availableActions: string[]; version: number;
}

export interface ReceiptInput {
  customerId: string; receiptDate: string; paymentMethod: string; paymentReference: string | null; amount: string;
  taxWithheld: string; remarks: string | null; allocations: { documentId: string; amount: string }[];
}

export interface ArAgingRow {
  customerId: string | null; customerCode: string | null; customerName: string; current: string; days1to30: string;
  days31to60: string; days61to90: string; over90: string; total: string;
}

export interface StatementLine {
  date: string; type: string; reference: string; description: string; charges: string; credits: string; balance: string;
}

export interface Statement {
  customerId: string; customerCode: string; customerName: string; from: string; to: string; openingBalance: string;
  lines: StatementLine[]; closingBalance: string; unappliedCollections: string;
}

export interface ControlReconciliation {
  key: string; controlAccountId: string; controlAccountCode: string; subledgerBalance: string; generalLedgerBalance: string;
  difference: string; reconciled: boolean;
}

export interface PortalAccount {
  customerCode: string; customerName: string; tin: string | null; address: string | null; paymentTermsDays: number;
  openBalance: string; overdueBalance: string; unappliedCollections: string; asOf: string;
}

export interface PortalBilling {
  id: string; kind: BillingKind; documentNumber: string; documentDate: string; dueDate: string; billingPeriod: string | null;
  description: string; relatedInvoiceNumber: string | null; netAmount: string; vatAmount: string; grossAmount: string;
  settledAmount: string; openBalance: string; lines: BillingLine[];
}

// ---------------------------------------------------------------- Phase 3c: treasury

export interface UndepositedReceipt {
  id: string; receiptNumber: string; customerName: string; receiptDate: string; paymentMethod: string;
  paymentReference: string | null; recordedBy: string; amount: string;
}

export interface DepositSummary {
  id: string; depositNumber: string; bankAccountCode: string; depositDate: string; bankReference: string | null; amount: string;
  items: number; status: string; preparedBy: string; updatedAt: string; version: number;
}

export interface DepositView {
  id: string; depositNumber: string; bankAccountId: string; bankAccountCode: string; bankName: string; depositDate: string;
  bankReference: string | null; amount: string; status: string; preparedBy: string; cancelReason: string | null;
  postedEntryId: string | null; postedEntryNumber: string | null;
  items: { receiptId: string; receiptNumber: string; customerName: string; receiptDate: string; paymentMethod: string;
    paymentReference: string | null; amount: string }[];
  documents: DocumentInfo[]; availableActions: string[]; version: number;
}

export interface DailyCollectionReport {
  date: string; collector: string | null; firstNumber: string | null; lastNumber: string | null;
  lines: { receiptNumber: string; customerName: string; paymentMethod: string; paymentReference: string | null; amount: string;
    taxWithheld: string; status: string; recordedBy: string; depositNumber: string | null; cancelReason: string | null }[];
  totals: { paymentMethod: string; count: number; amount: string }[];
  totalCollected: string; totalTaxWithheld: string; undeposited: string; cancelled: number; note: string;
}

export interface CashPosition {
  asOf: string; cashOnHandBook: string; undepositedCollections: string; totalBank: string;
  banks: { bankAccountId: string; code: string; bankName: string; last4: string; glAccountCode: string; bookBalance: string;
    lastReconciledStatementEnd: string | null }[];
}

export interface CheckRow {
  id: string; bankAccountCode: string; checkNumber: string; dvNumber: string; payee: string; amount: string; issueDate: string;
  status: string; voidReason: string | null; ageDays: number;
}

export interface StatementSummary {
  id: string; bankAccountId: string; bankAccountCode: string; format: string; statementReference: string | null;
  periodStart: string; periodEnd: string; openingBalance: string; closingBalance: string; lineCount: number;
  unmatchedLines: number; fileName: string; importedAt: string; importedBy: string; reconciliationId: string | null;
  reconciliationStatus: string | null;
}

export interface StatementLine {
  id: string; lineNo: number; valueDate: string; bookingDate: string | null; amount: string; description: string;
  bankReference: string | null; matchId: string | null;
}

export interface LedgerItem {
  journalLineId: string; entryNumber: string; accountingDate: string; description: string; reference: string | null; amount: string;
}

export interface BankMatch {
  id: string; method: string; rule: string; createdBy: string; createdAt: string; statementLines: StatementLine[];
  ledgerLines: LedgerItem[]; locked: boolean;
}

export interface ReconciliationFigures {
  bankBalance: string; depositsInTransit: string; outstandingChecks: string; adjustedBank: string; bookBalance: string;
  unrecordedCredits: string; unrecordedDebits: string; adjustedBook: string; difference: string;
}

export interface ReconciliationView {
  id: string; bankAccountId: string; bankAccountCode: string; statementId: string; periodStart: string; asOfDate: string;
  status: string; currentStep: string | null; figures: ReconciliationFigures; unmatchedStatementLines: StatementLine[];
  unmatchedLedgerLines: LedgerItem[]; matches: BankMatch[]; warnings: string[]; preparedBy: string; approvedBy: string | null;
  approvedAt: string | null; decisions: Decision[]; availableActions: string[]; version: number;
}

// ---------------------------------------------------------------- Phase 3d: fixed assets

export interface AssetCategory {
  id: string; code: string; name: string; assetAccountId: string; assetAccountCode: string; accumulatedAccountId: string;
  accumulatedAccountCode: string; expenseAccountId: string; expenseAccountCode: string; defaultUsefulLifeMonths: number;
  defaultResidualRate: string | null; status: string; placeholder: boolean;
}

export interface AucProject {
  id: string; code: string; name: string; aucAccountId: string; aucAccountCode: string; subsidiaryAccountId: string;
  subsidiaryCode: string; costCenterId: string | null; status: string; costIncurred: string; capitalized: string; balance: string;
  version: number;
}

export interface AssetSummary {
  id: string; assetNumber: string | null; description: string; categoryCode: string; location: string | null; custodian: string | null;
  inServiceDate: string; cost: string; accumulatedDepreciation: string; netBookValue: string; status: string; version: number;
}

export interface AssetInput {
  categoryId: string; description: string; serialNumber: string | null; location: string | null; custodian: string | null;
  costCenterId: string | null; acquisitionSource: 'INVOICE' | 'AUC' | 'OTHER'; supplierInvoiceId: string | null;
  aucProjectId: string | null; offsetAccountId: string | null; justification: string | null; acquisitionDate: string;
  inServiceDate: string; cost: string; residualValue: string | null; usefulLifeMonths: number | null;
}

export interface DisposalView {
  id: string; assetId: string; assetNumber: string | null; assetDescription: string; disposalDate: string; method: string;
  proceeds: string; proceedsAccountCode: string | null; reason: string; status: string; cost: string; accumulated: string;
  netBookValue: string; gainLoss: string; preparedBy: string; postedEntryId: string | null; postedEntryNumber: string | null;
  availableActions: string[]; version: number;
}

export interface AssetView {
  id: string; assetNumber: string | null; description: string; serialNumber: string | null; location: string | null;
  custodian: string | null; categoryId: string; categoryCode: string; categoryName: string; costCenterId: string | null;
  costCenterCode: string | null; acquisitionSource: string; supplierInvoiceId: string | null; supplierInvoiceNumber: string | null;
  aucProjectId: string | null; aucProjectCode: string | null; offsetAccountCode: string | null; justification: string | null;
  acquisitionDate: string; inServiceDate: string; cost: string; residualValue: string; usefulLifeMonths: number;
  monthlyDepreciation: string; accumulatedDepreciation: string; netBookValue: string; status: string; preparedBy: string;
  activatedBy: string | null; activatedAt: string | null; acquisitionEntryId: string | null; acquisitionEntryNumber: string | null;
  depreciation: { periodName: string; periodEnd: string; amount: string; runStatus: string; entryNumber: string | null }[];
  disposal: DisposalView | null; availableActions: string[]; version: number;
}

export interface DepreciationRunSummary {
  id: string; periodName: string; periodEnd: string; status: string; totalAmount: string; assetCount: number; preparedBy: string;
  updatedAt: string;
}

export interface DepreciationRun {
  id: string; periodId: string; periodName: string; periodEnd: string; status: string; totalAmount: string; assetCount: number;
  preparedBy: string; postedEntryId: string | null; postedEntryNumber: string | null;
  lines: { assetId: string; assetNumber: string; description: string; categoryCode: string; amount: string; accumulatedAfter: string;
    netBookValueAfter: string }[];
  availableActions: string[]; version: number;
}

export interface PpeRow {
  categoryCode: string | null; categoryName: string; openingCost: string; additions: string; disposalsCost: string; closingCost: string;
  openingAccumulated: string; depreciation: string; disposalsAccumulated: string; closingAccumulated: string; netBookValue: string;
}

// ---------------------------------------------------------------- Phase 3e: inventory

export interface Warehouse { id: string; code: string; name: string; status: string }

export interface InventoryItem {
  id: string; code: string; name: string; unitOfMeasure: string; inventoryAccountId: string; inventoryAccountCode: string;
  consumptionAccountId: string; consumptionAccountCode: string; status: string; quantity: string; value: string;
  averageCost: string | null; warehouses: { warehouseId: string; warehouseCode: string; quantity: string }[];
}

export type InventoryKind = 'GOODS_RECEIPT' | 'ISSUE' | 'COUNT' | 'TRANSFER' | 'REVALUATION';

export interface InventoryLineInput { itemId: string; warehouseId: string | null; toWarehouseId: string | null; quantity: string; unitCost: string | null }

export interface InventoryDocumentSummary {
  id: string; documentNumber: string | null; kind: InventoryKind; documentDate: string; supplierName: string | null;
  reference: string | null; description: string; status: string; totalValue: string | null; preparedBy: string; updatedAt: string;
  version: number;
}

export interface InventoryDocument {
  id: string; documentNumber: string | null; kind: InventoryKind; documentDate: string; supplierId: string | null;
  supplierName: string | null; reference: string | null; costCenterId: string | null; costCenterCode: string | null;
  description: string; status: string; totalValue: string | null; preparedBy: string; postedBy: string | null;
  postedEntryId: string | null; postedEntryNumber: string | null;
  lines: { lineNo: number; itemId: string; itemCode: string; itemName: string; unitOfMeasure: string; warehouseId: string;
    warehouseCode: string; toWarehouseCode: string | null; quantity: string; unitCost: string | null; systemQuantity: string | null;
    value: string | null }[];
  availableActions: string[]; version: number;
}

export interface InventoryMovement {
  createdAt: string; movementDate: string; documentNumber: string; kind: string; movementType: string; warehouseCode: string;
  quantity: string; value: string; poolQuantity: string; poolValue: string; averageAfter: string | null;
}

export interface GrIrStatus {
  receipts: { id: string; documentNumber: string; documentDate: string; supplierId: string; supplierName: string; value: string;
    matched: string; open: string }[];
  invoices: { id: string; invoiceNumber: string; invoiceDate: string; supplierId: string; supplierName: string; grIrAmount: string;
    matched: string; available: string }[];
  openReceipts: string; unmatchedInvoices: string;
}

// ---------------------------------------------------------------- Phase 4a: financial reporting

export interface StatementTemplate {
  id: string; code: string; version: number; name: string; statementType: 'SFP' | 'SFPERF' | 'SCF' | 'SCE'; status: string; placeholder: boolean;
  balanceCheck: string | null; createdBy: string; createdAt: string; activatedBy: string | null; activatedAt: string | null;
  lines: { lineNo: number; code: string; label: string; type: string; fsLineCodes: string[]; totalOf: string[]; sign: number; indent: number;
    counterpartCodes: string[] }[];
  availableActions: string[];
}

export interface StatementResult {
  templateId: string; templateCode: string; templateVersion: number; title: string; statementType: string; periodName: string;
  periodEnd: string; comparativePeriodName: string | null;
  lines: { code: string; label: string; type: string; indent: number; current: string | null; comparative: string | null }[];
  balanced: boolean | null; unmapped: { accountCode: string; accountName: string; fsLineCode: string | null; balance: string }[];
  /** Movement statements: parts of a change no line classifies (debit positive). */
  unclassified: { fsLineCodes: string[]; counterpartCode: string; amount: string }[];
  placeholderData: boolean; notes: string[];
}

export interface ReportJob {
  id: string; reportType: string; format: string; parameters: Record<string, string>; status: string; requestedBy: string;
  requestedAt: string; startedAt: string | null; finishedAt: string | null; error: string | null; fileName: string | null;
  sizeBytes: number | null; sha256: string | null; expiresAt: string | null;
}

// ---------------------------------------------------------------- Phase 4c: integrations and payment files

export interface IntegrationChannel { channel: string; mode: string; simulated: boolean; availability: string; counts: Record<string, number> }

export interface Transmission {
  id: string; channel: string; documentType: string; documentId: string; idempotencyKey: string; payload: unknown; payloadSha256: string;
  status: string; attempts: number; nextAttemptAt: string; lastError: string | null; externalReference: string | null; simulated: boolean;
  requestedBy: string; createdAt: string; updatedAt: string;
  attemptLog: { attemptNo: number; startedAt: string; finishedAt: string; outcome: string; endpoint: string | null; httpStatus: number | null;
    responseExcerpt: string | null }[];
}

export interface SupplierBankAccount {
  id: string; supplierId: string; bankName: string; branch: string | null; accountName: string; accountNumberMasked: string; status: string;
  enteredBy: string; enteredAt: string; verifiedBy: string | null; verifiedAt: string | null; verificationNote: string | null;
  availableActions: string[];
}

export interface EligibleDv {
  dvId: string; dvNumber: string; supplierName: string; amount: string; payeeBank: string | null; payeeAccountMasked: string | null;
  blockedReason: string | null; version: number;
}

export interface PaymentBatch {
  id: string; batchNumber: string; bankAccountCode: string; paymentDate: string; itemCount: number; totalAmount: string; fileName: string;
  sha256: string; createdBy: string; createdAt: string; downloadCount: number;
  items: { lineNo: number; dvNumber: string; payee: string; account: string; amount: string; dvStatus: string }[];
}

// ---------------------------------------------------------------- Phase 4d: tickets

export interface TicketSummary {
  id: string; ticketNumber: string; ticketType: string; priority: string; severity: string | null; category: string | null; title: string;
  status: string; reporter: string;
  assignee: string | null; resolutionDueAt: string | null; breached: boolean; escalationLevel: number; createdAt: string;
}

export interface TicketCategory { id: string; code: string; name: string; sortOrder: number; status: 'ACTIVE' | 'INACTIVE'; placeholder: boolean }

export interface TicketView {
  id: string; ticketNumber: string; ticketType: string; priority: string; severity: string | null; category: string | null; categoryId: string | null;
  title: string; description: string; module: string | null;
  status: string; reporter: string; assignee: string | null; contractPhase: string;
  sla: { policy: string | null; businessHours: boolean; responseDueAt: string | null; resolutionDueAt: string | null; firstResponseAt: string | null;
    responseMinutesLeft: number | null; resolutionMinutesLeft: number | null; paused: boolean; responseBreached: boolean;
    resolutionBreached: boolean; escalationLevel: number };
  createdAt: string; resolvedAt: string | null; closedAt: string | null; closureConfirmed: boolean | null; satisfaction: number | null;
  change: { rollbackPlan: string | null; stagingValidatedAt: string | null; stagingValidatedBy: string | null; stagingValidationNote: string | null;
    releaseNotes: string | null; releaseReference: string | null } | null;
  documents: DocumentInfo[];
  events: { seq: number; occurredAt: string; actor: string; eventType: string; fromValue: string | null; toValue: string | null;
    comment: string | null; internal: boolean }[];
  availableActions: string[]; version: number;
}

export interface SlaPolicy {
  id: string; contractPhase: string; ticketType: string; priority: string; responseMinutes: number; resolutionMinutes: number;
  businessHours: boolean; placeholder: boolean; status: string; createdBy: string; createdAt: string;
}

export interface BusinessCalendar { zone: string; start: string; end: string; workdays: string[]; contractPhase: string;
  holidays: { date: string; name: string }[] }

// ---------------------------------------------------------------- Phase 4e: portal onboarding

export interface PortalLinkRequest {
  id: string; customerId: string; customerCode: string; customerName: string; userId: string; username: string; displayName: string;
  email: string; status: string; evidence: string; requestedBy: string; requestedAt: string; decidedBy: string | null; decidedAt: string | null;
  decisionNote: string | null; documents: DocumentInfo[]; availableActions: string[]; version: number;
}

// ---------------------------------------------------------------- Phase 5a: opening-balance migration

export type MigrationKind = 'GL_OPENING' | 'AR_OPEN_ITEMS' | 'AP_OPEN_ITEMS' | 'AR_UNAPPLIED' | 'AP_ADVANCES' | 'FA_REGISTER'
  | 'INVENTORY_STOCK';

export interface MigrationBatchSummary {
  id: string; kind: MigrationKind; status: string; description: string; fileName: string; fileSha256: string; rowCount: number;
  errorCount: number; totalAmount: string; cutoverDate: string; openingEntryNumber: string | null; journalVoucherId: string | null;
  journalVoucherNumber: string | null; journalVoucherStatus: string | null; uploadedBy: string; uploadedAt: string; appliedBy: string | null;
  appliedAt: string | null; cancelReason: string | null; version: number;
}

export interface MigrationRow { rowNo: number; data: Record<string, string>; errors: string[] }

export interface MigrationPartyBalance { partyCode: string; partyName: string; ledger: string; batch: string; difference: string }

export interface MigrationBatchDetail {
  summary: MigrationBatchSummary; columns: string[]; batchErrors: string[]; errorRows: MigrationRow[]; firstRows: MigrationRow[];
  reconciliation: { controlAccountCode: string; ledgerTotal: string; batchTotal: string; partiesMatched: number;
    differences: MigrationPartyBalance[]; reconciled: boolean } | null;
  documents: DocumentInfo[]; availableActions: string[];
}

// ---------------------------------------------------------------- Phase 5e: parallel-run comparison

export interface ParallelRunSummary {
  id: string; periodId: string; periodName: string; basis: 'CLOSING' | 'PERIOD'; legacySystem: string; fileName: string; fileSha256: string;
  status: string; legacyTotal: string; casTotal: string; comparedAt: string; lineCounts: Record<string, number>; unexplained: number;
  uploadedBy: string; uploadedAt: string; signedOffBy: string | null; signedOffAt: string | null; signOffNote: string | null;
  cancelReason: string | null; version: number;
}

export interface ParallelRunLine {
  id: string; lineType: 'MATCHED' | 'DIFFERENT' | 'CAS_ONLY' | 'LEGACY_ONLY' | 'UNMAPPED'; accountId: string | null; accountCode: string | null;
  accountName: string | null; legacyCodes: string | null; legacyAmount: string; casAmount: string; difference: string;
  explanation: string | null; explainedBy: string | null; explainedAt: string | null;
}

export interface ParallelRunDetail { summary: ParallelRunSummary; lines: ParallelRunLine[]; documents: DocumentInfo[]; availableActions: string[] }

// ---------------------------------------------------------------- dashboard, schedules, portal inquiries

export interface MonthAmount { month: string; amount: string }
export interface NamedAmount { code: string; name: string; amount: string }
export interface AgingTotals { current: string; days1to30: string; days31to60: string; days61to90: string; over90: string; total: string }

export interface Dashboard {
  asOf: string;
  cash: { cashOnHand: string; undeposited: string; banks: NamedAmount[]; total: string } | null;
  receivables: { aging: AgingTotals; topCustomers: NamedAmount[]; collections: MonthAmount[]; unapplied: string } | null;
  payables: { aging: AgingTotals; dueNext30Days: string; topSuppliers: NamedAmount[]; disbursements: MonthAmount[] } | null;
  budget: { fiscalYear: number; allotment: string; reserved: string; actual: string; available: string; utilisationPercent: string;
    highestUtilisation: { account: string; costCenter: string | null; allotment: string; used: string; utilisationPercent: string }[] } | null;
  workQueue: { label: string; count: number; link: string }[];
  tickets: { openAssignedToMe: number; openTotal: number; breached: number } | null;
  notes: string[];
}

export interface ReportSchedule {
  id: string; name: string; reportType: string; format: string; parameters: Record<string, string>; frequency: string; runHour: number;
  dayOfWeek: number | null; dayOfMonth: number | null; status: string; statusReason: string | null; nextRunAt: string; lastRunAt: string | null;
  lastJobId: string | null; emailWhenReady: boolean; version: number;
}

export interface PortalInquiry {
  id: string; reference: string; customerId: string; customerCode: string; customerName: string; kind: 'PAYMENT_REFERENCE' | 'QUESTION';
  subject: string; message: string; billingId: string | null; billingNumber: string | null; paymentDate: string | null;
  paymentAmount: string | null; paymentMethod: string | null; paymentReference: string | null; status: string; response: string | null;
  respondedBy: string | null; respondedAt: string | null; submittedBy: string; createdAt: string; documents: DocumentInfo[];
  availableActions: string[]; version: number;
}

export interface InboxItem {
  queue: string; documentType: string; id: string; number: string | null; description: string | null; counterparty: string | null;
  amount: string | null; date: string | null; status: string; task: string; preparedBy: string; waitingSince: string; link: string;
}

export interface Inbox { items: InboxItem[]; queues: { queue: string; count: number }[]; truncated: boolean }

export interface AppNotification {
  id: string; category: string; title: string; body: string | null; link: string | null; createdAt: string; readAt: string | null;
}

export interface RoleGrant {
  id: string; userId: string; username: string; displayName: string; rolesBefore: string[]; rolesRequested: string[];
  financialAdded: string[]; status: string; requestedBy: string; requestedAt: string; decidedBy: string | null; decidedAt: string | null;
  decisionNote: string | null; availableActions: string[]; version: number;
}

export interface AccessReviewCycle {
  id: string; name: string; dueDate: string; status: string; startedBy: string; startedAt: string; closedBy: string | null;
  closedAt: string | null; total: number; reviewed: number; actionsOpen: number; version: number;
}

export interface AccessReviewItem {
  id: string; userId: string; username: string; displayName: string; rolesAtStart: string[]; rolesNow: string[]; financial: boolean;
  outcome: 'RETAIN' | 'MODIFY' | 'REVOKE' | null; reviewer: string | null; reviewedAt: string | null; notes: string | null;
  actionedBy: string | null; actionedAt: string | null; actionNote: string | null; availableActions: string[]; version: number;
}

export interface AccessReviewCycleDetail { summary: AccessReviewCycle; items: AccessReviewItem[]; availableActions: string[] }

export interface ApprovalLimit { roleCode: string; documentType: string; maxAmount: string; updatedBy: string; updatedAt: string }

export interface OrgDepartment { id: string; officeId: string; code: string; name: string; status: string; costCenterId: string | null;
  costCenterCode: string | null; currentUsers: number; version: number }
export interface OrgOffice { id: string; organizationId: string; code: string; name: string; status: string; departments: OrgDepartment[];
  currentUsers: number; version: number }
export interface Organization { id: string; code: string; name: string; tin: string | null; status: string; offices: OrgOffice[]; version: number }
export interface OfficeAssignment { id: string; userId: string; username: string; officeId: string; officeCode: string; officeName: string;
  departmentId: string | null; departmentCode: string | null; departmentName: string | null; effectiveFrom: string; effectiveTo: string | null;
  createdBy: string; endedBy: string | null }

export interface AssetTransfer {
  id: string; assetId: string; assetNumber: string; transferDate: string; fromCostCenterId: string | null; fromCostCenter: string | null;
  toCostCenterId: string | null; toCostCenter: string | null; fromLocation: string | null; toLocation: string | null; fromCustodian: string | null;
  toCustodian: string | null; reason: string; status: string; preparedBy: string; postedBy: string | null; postedAt: string | null;
  entryNumber: string | null; availableActions: string[]; version: number;
}

export interface OpenItemAccount { id: string; code: string; name: string; openItems: number; openNet: string }

export interface GlOpenItem {
  lineId: string; entryNumber: string; accountingDate: string; description: string | null; subsidiaryAccountId: string | null;
  subsidiaryCode: string | null; debit: string; credit: string; ageDays: number;
}

export interface ClearingProposal { key: string; lineIds: string[]; total: string }

export interface Clearing {
  id: string; rule: 'MANUAL' | 'REFERENCE' | 'AMOUNT'; lineCount: number; total: string; note: string | null; status: 'ACTIVE' | 'REVERSED';
  clearedBy: string; clearedAt: string; reversedBy: string | null; reversalReason: string | null; entryNumbers: string[];
}

export type CloseFrequency = 'DAY_END' | 'MONTH_END' | 'QUARTER_END' | 'YEAR_END';

export interface CloseTaskItem {
  taskId: string; code: string; name: string; instructions: string | null; responsiblePermission: string; dueDate: string; overdue: boolean;
  status: 'OPEN' | 'DONE' | 'NOT_APPLICABLE'; note: string | null; completedBy: string | null; completedAt: string | null;
  canComplete: boolean; placeholder: boolean;
}

export interface CloseCalendarChecklist {
  frequency: CloseFrequency; scopeKey: string; label: string; scopeEnd: string;
  checks: { code: string; description: string; outcome: 'PASS' | 'FAIL' | 'NOT_AVAILABLE'; detail: string; exceptions: string[] }[];
  tasks: CloseTaskItem[];
}

export interface CloseTask {
  id: string; code: string; name: string; frequency: CloseFrequency; dueOffsetDays: number; responsiblePermission: string;
  instructions: string | null; sortOrder: number; status: 'ACTIVE' | 'INACTIVE'; placeholder: boolean; version: number;
}

export interface CloseScheduleItem {
  taskId: string; taskName: string; frequency: CloseFrequency; scopeKey: string; scopeLabel: string; dueDate: string;
  status: 'OPEN' | 'DONE' | 'NOT_APPLICABLE'; overdue: boolean;
}
