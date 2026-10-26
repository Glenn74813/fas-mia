# CAS User Manual

For MIAA finance staff, approvers, auditors and support staff (version for schema V41, October 2026). The
concessionaire portal has its own chapter (§14). Administrators also read the [administrator manual](admin-manual.md).

## 1. Basics

### 1.1 Signing in

You need your username (or e-mail), your password and an authenticator app on your phone, such as Google
Authenticator or Microsoft Authenticator.

1. Open CAS and enter your **Username or e-mail** and **Password**; choose **Continue**.
2. Enter the **6-digit code** shown by your authenticator app; choose **Verify**.

**First sign-in.** Your administrator gives you a temporary password.
1. Sign in with it. CAS shows a set-up code: scan it with your authenticator app, or type the secret.
2. Enter the 6-digit code to activate the app.
3. CAS then asks you to choose your own password: **Current password**, **New password**,
   **Confirm new password**, **Save password**.

Passwords:
* 12 to 128 characters, with at least three of upper case, lower case, digits and symbols;
* must not contain your username or e-mail name;
* must not be a common password.

**Forgotten password.**
1. On the sign-in page choose the reset link, enter your **Username or e-mail** and choose **Send reset link**.
2. The e-mailed link works once and for 30 minutes. Choose a **New password** and **Save password**.
3. You still need your authenticator code to sign in.

| Rule | What you see |
|---|---|
| Five wrong passwords in a row lock the account for 15 minutes | A general sign-in error; it never says whether the account exists or is locked |
| A code can be used only once | Wait for the next code (30 seconds) |
| Sessions end after 8 hours | You are asked to sign in again |
| Lost phone | Ask an administrator to **Reset MFA**; you enrol again at the next sign-in |

### 1.2 The screen

* **Navigation.** The menu on the left shows only the areas your permissions allow. Each page shows a breadcrumb
  and its actions at the top right.
* **Dashboard.** Shows the figures your role may see:
  * cash in banks and on hand;
  * collections not yet deposited;
  * open receivables and payables with past-due and next-30-day amounts;
  * budget allotment, reserved, actual and available;
  * tickets assigned to you and those past their SLA;
  * **Waiting for you**, a count of items you may act on.

  The indicators are proposals until MIAA approves its KPIs.
* **Inbox.** Every document waiting for a step *you* may take, oldest first, in one list: vouchers to review,
  approve or post; invoices, disbursement vouchers, billings, budgets, reconciliations, asset transfers and
  others; close tasks due. Use **Filter by queue** or the search box, then **Open** to act on the item's own page.
  You never see your own documents waiting for your approval: the preparer cannot approve.
* **Notifications** (bell icon). Tells you when someone approved, returned, rejected or posted your document,
  when a report finished, or when a request was decided.
* **Sign out** is at the top right.

### 1.3 Rules that apply everywhere

* **Two people.** Whoever prepares a document cannot approve, post, apply or verify it. A person takes at most one
  approval step on a document.
* **Posted means final.** A posted entry is never changed or deleted. Corrections are new documents: a reversal,
  a credit or debit memo, an adjustment.
* **Closed periods.** Nothing can be posted into a closed period. Reopening needs a request and a second
  person's approval (§3.4).
* **Someone else changed it.** If a document changed after you opened it, the action is refused with "modified
  by someone else". Reload the page and try again.
* **Attachments.** PDF, PNG, JPEG, CSV, XLSX and DOCX up to 10 MB. Files are checked against their type; HTML or
  disguised files are refused. Until a scanning engine is chosen (C-25), files show **NOT SCANNED**.
* **Amounts** are in Philippine pesos with two decimals.

## 2. Journal vouchers

| Task | Who (permission) |
|---|---|
| Prepare, attach, submit | Accountant (`JOURNAL_CREATE`, `JOURNAL_SUBMIT`) |
| Review | Accountant or chief accountant (`JOURNAL_REVIEW`), not the preparer |
| Approve | Chief accountant (`JOURNAL_APPROVE`) |
| Post, reverse | Chief accountant (`JOURNAL_POST`, `JOURNAL_REVERSE`), not the preparer |

**Prepare.**
1. **Journal vouchers** → **New**.
2. Enter the **Accounting date**, **Description / particulars** and an optional **Supporting reference**.
3. Add lines: account, cost centre or subsidiary account where the account requires them, and a debit or credit.
   Debits must equal credits.
4. Choose **Save draft**.
5. On the voucher, **Attach file** for the supporting documents (required for most voucher types).
6. Choose **Submit**.

**Review and approve.** Open the voucher from the Inbox. Check the lines, attachments and **Approval progress**,
then choose:
* **Approve step**, to pass it to the next step;
* **Return**, with a comment, to send it back to the preparer;
* **Reject**, which is final.

**Post.** On an approved voucher choose **Post**. CAS creates the journal entry with the next gap-free number;
the numbering format is interim (C-23). **Reverse** creates a reversing entry; an entry can be reversed once.

Budget control: on budget-controlled accounts, posting is refused if no approved budget allocation covers the
amount ("Insufficient budget for …").

**Recurring journals** (`RECURRING_JOURNAL_MANAGE`):
1. **Recurring journals** → **New template**.
2. Enter the **Name**, **Voucher description**, **Day of month (1-28)**, **First run**, an optional
   **End date (optional)** and balanced lines. **Save template**.
3. **Generate due vouchers** creates drafts. Each draft then goes through the normal workflow.

## 3. General ledger

### 3.1 Trial balance and account ledger
* **Trial balance**: choose a period. Debits and credits per account, with totals.
* **Account ledger**: choose an account (and optionally a subsidiary account) and a date range; open any entry
  to see its lines and source.

### 3.2 Open items (`LEDGER_VIEW` to view, `GL_CLEARING` to clear)

Some accounts, such as GR/IR, suspense and advances, are managed by open item. Each posted line stays open until
it is matched with lines that net to zero.
1. **Open items** → choose an account under **Managed accounts**.
2. Tick the lines to match. The selected net must be zero. Enter a **Clearing note** and choose **Clear selected**.
3. Or choose an **Automatic clearing rule**:
   * *By reference*: the same description, netting to zero;
   * *By amount*: an equal and opposite pair, oldest first.

   Then **Preview**, and **Apply** if the proposals are right.
4. **Clearing history** lists the clearings. **Reverse**, with a reason, re-opens the items.

Clearing posts nothing. Items open more than 90 days appear on the month-end checklist.

### 3.3 Fiscal periods (`PERIOD_OPEN`, `PERIOD_CLOSE`)
* **Open** a FUTURE period to allow posting; **Soft-close** to stop routine posting while closing.
* **Close…** shows the close checklist. Automatic checks:
  * unposted vouchers;
  * earlier periods closed;
  * debits equal credits;
  * ledger cache consistent;
  * bank reconciliations;
  * depreciation posted;
  * AP, AR, fixed-asset and inventory reconciled;
  * open items aged over 90 days.
* A failing check that can be waived needs **waive** ticked and a **Justification for waivers** of at least ten
  characters. Checks that cannot be waived must be fixed. Then choose **Close period**.
* Year end: choose the **Accumulated surplus account** and **Generate closing voucher**, which then goes through
  the normal workflow. After the last period closes, choose **Close year**.

### 3.4 Reopening a period
1. On a closed period choose **Request reopen**, give a **Reason (at least 10 characters)** and **Submit request**.
2. A finance manager (`PERIOD_REOPEN`) sees it under **Pending reopen requests** and chooses **Approve** or
   **Reject**. The requester cannot decide their own request.

### 3.5 Close calendar (`LEDGER_VIEW`)
1. **Close calendar** → choose **Day end**, **Month end**, **Quarter end** or **Year end**, then the day, period,
   quarter or fiscal year.
2. **Automatic checks** show PASS, FAIL or N/A, and list the exceptions. Day-end checks:
   * posted collections not yet deposited;
   * collections recorded but not posted;
   * payments released but not posted;
   * dead or rejected integrations.
3. **Tasks** are the manual steps of the close calendar. Each shows its due date and owner. Tasks are
   [PLACEHOLDER] until MIAA supplies its calendar (C-33).
4. To tick off a task you own, choose the **Outcome** (Done or Not applicable), write a **Note** and choose
   **Complete task**. Only holders of the task's responsible permission can complete it.
5. The **Closing schedule** lists what falls due between two dates. Tasks due in the last week also appear in
   your Inbox.

## 4. Payables

| Task | Who |
|---|---|
| Suppliers and their bank details | Accountant (`SUPPLIER_MANAGE`) |
| Verify supplier bank details | Chief accountant (`SUPPLIER_BANK_VERIFY`), not the person who entered them |
| Supplier invoices: prepare, approve, post | Accountant (`AP_INVOICE_PREPARE`); chief accountant (`AP_INVOICE_APPROVE`); poster (`JOURNAL_POST`) |
| Disbursement vouchers (DVs) | Disbursement officer prepares; accountant certifies (`DV_CERTIFY`); finance manager approves (`PAYMENT_APPROVE`); cashier issues and confirms the payment (`PAYMENT_RELEASE`); chief accountant posts |

**Suppliers.**
1. **Suppliers** → **New supplier**: **Code**, **Registered name**, **TIN**, **Address**, **VAT-registered** and the
   **Default withholding tax**. **Save supplier**.
2. **Bank details**: **Bank**, **Branch**, **Account name**, **Account number** → **Save for verification**.
3. A second person verifies the details with a **Verification note** before they can be used for payment.

**Supplier invoices.**
1. **Supplier invoices** → new invoice: **Supplier**, **Supplier invoice number**, **Invoice date**, **Due date**,
   **Accounting date**, **Description**, and lines with tax codes (VAT and withholding are [PLACEHOLDER] codes
   pending BIR confirmation).
2. **Save draft**, attach the invoice, then **Submit**.
3. The approver chooses **Approve**, **Return** or **Reject**; a poster chooses **Post**.

Submitting reserves budget on budget-controlled accounts.

**Disbursement vouchers.**
1. **Disbursement vouchers** → **New**: **Payee (supplier)**, **Particulars** and the invoices to pay.
   **Save draft**, attach, then **Submit**.
2. The voucher passes **Certify**, then **Approve**.
3. The cashier chooses **Issue payment** (cheque or bank transfer), then **Confirm release**.
4. The poster chooses **Post**. **Void payment** cancels an issued payment that was not released.

**Payables reports**: aging, open items and the withholding schedule (choose **Year** and **Quarter**).

## 5. Receivables

| Task | Who |
|---|---|
| Customers and contracts | Accountant (`CUSTOMER_MANAGE`) |
| Billing: prepare, approve, post | Accountant (`AR_BILLING_PREPARE`); chief accountant (`AR_BILLING_APPROVE`); poster |
| Collections | Cashier (`COLLECTION_RECORD`); posted by a second person |
| Portal access and inquiries | `PORTAL_ACCESS_APPROVE` (chief accountant); `PORTAL_INQUIRY_RESPOND` (accountant, cashier) |

**Customers.**
1. **Customers** → **New customer**: **Code**, **Registered name**, **TIN**, **Address**, **Billing e-mail**,
   **Credit limit**, **Payment terms (days)**. **Save customer**.
2. On the customer, **Add contract** to set up monthly billing: **Contract number**, **Description**,
   **Start date**, **End date**, **Monthly amount (net of VAT)**. **Save contract**.

**Billing.**
* **Billing** → **Generate drafts** for a **Billing month** creates contract invoices; each contract is billed
  once per month.
* Or create one manually: **Customer**, **Document date**, **Due date**, **Description**, lines. Credit and debit
  memos refer to an invoice.
* Submit, approve and post as for invoices. Proration and penalties are not built (C-28). Credit limits warn but
  do not block (C-27).

**Collections.**
1. **Collections** → **New**: **Customer**, **Receipt date**, **Amount received**,
   **Tax withheld by customer**, the payment method and its reference (required except for cash), **Remarks**.
   **Save receipt**.
2. Allocate the receipt to open invoices; any remainder stays unapplied.
3. A second person chooses **Post**. Later, **Apply unapplied** allocates an unapplied balance.

Receipt numbers are an interim series, not official receipts (C-29). A receipt is recorded once even if you
press save twice.

**Portal inquiries**: answer concessionaires' questions and acknowledge their payment references. A payment
reference is information for the cashier; it is not a receipt.

## 6. Treasury

| Task | Who |
|---|---|
| Deposits | Cashier (`DEPOSIT_PREPARE`); posted by a second person |
| Bank statements and reconciliation | Accountant (`BANK_STATEMENT_IMPORT`, `BANK_RECONCILIATION_PREPARE`); finance manager approves (`BANK_RECONCILIATION_APPROVE`) |
| Payment files | Cashier (`PAYMENT_RELEASE`) |

* **Deposits** → **New**:
  1. Enter the **Deposit date** and **Deposit slip number** and choose the posted collections to deposit. A
     collection can be deposited once.
  2. **Save deposit**, attach the validated slip.
  3. A second person posts it.
* **Daily collections**: the daily collection report for a date; **Print**.
* **Bank statements**:
  1. **Import** a statement file (CSV or CAMT.053) for a bank account, with its **Opening balance** and
     **Closing balance**. A gap against the previous statement is warned.
  2. Choose **Reconcile**: **Auto-match** proposes matches; **Match selected** matches statement lines with ledger
     lines of equal total.
  3. Submit the reconciliation for approval.

  Bank-specific formats are pending (C-11).
* **Payment files**: **Create payment file** for a **Payment date** builds an ADA batch from released payments to
  verified supplier accounts. The layout is pending the bank format (C-11).
* **Cash position**: book balance per bank account and the cheque register by **Check status**.

## 7. Fixed assets

| Task | Who |
|---|---|
| Register, activate, transfer, dispose | Accountant (`FA_MANAGE`) prepares; a second person activates or posts |
| Depreciation runs | Prepared by one person, posted by another |

* **Asset register** → **Register asset**:
  1. Enter the category, **Description**, **Serial number**, **Acquisition date**, **In-service date**, **Cost**,
     **Residual value**, **Useful life (months)**, **Location** and **Custodian**.
  2. Choose the source of funding. For an acquisition not paid through payables (for example a donation), give
     the offset account and a **Justification**.
  3. **Save draft**; a second person chooses **Activate**.

  Category lives and rates are [PLACEHOLDER] (C-30).
* **Transfers** (on the asset):
  1. Enter the **Transfer date**, the new cost centre, **To location**, **To custodian** and the
     **Reason / authority**; **Prepare transfer**.
  2. A second person posts it. A cost-centre change also moves the cost and accumulated depreciation in the ledger.
* **Depreciation**:
  1. **Compute depreciation** for a period; review the lines.
  2. A second person posts the run. One run per period; in annual mode, only in the year's last period.
* **Disposal**: **Dispose** with the **Disposal date**, **Proceeds** and **Reason / authority**. A second person
  chooses **Post disposal**. A pending transfer blocks a disposal and the other way round.
* **Asset setup**: categories and assets-under-construction projects. **PPE reports**: the PPE schedule.

## 8. Inventory

| Task | Who |
|---|---|
| Items, warehouses, stock documents | Inventory clerk (`INVENTORY_MANAGE`); posted by a second person |

* **Stock on hand**: items, warehouses and quantities; **Add item** (**Item code**, **Item name**,
  **Unit of measure**), **Add warehouse**.
* **Stock documents** → **New**. Kinds:
  * goods receipt (from a **Supplier**);
  * issue to a cost centre;
  * transfer between warehouses;
  * count;
  * price adjustment, which restates an item's average cost.

  Enter the lines and **Save draft**; a second person reviews and posts.
* **Import lines**: upload a CSV (`item_code`, `warehouse_code`, `quantity`, plus `unit_cost` for receipts and
  counts, `to_warehouse_code` for transfers; `item_code` and `unit_cost` for a price adjustment), then
  **Import as draft**. Every row is checked; nothing is created if any row is wrong.
* **Inventory reports**: valuation, GR/IR status; **Match** a supplier invoice to goods receipts with the
  **Amount to match**.

## 9. Budgets

| Task | Who |
|---|---|
| Prepare, request adjustments | Budget officer (`BUDGET_PREPARE`) |
| Approve budgets and decide adjustments | Finance manager (`BUDGET_APPROVE`), not the preparer or requester |

1. **Budgets** → choose the **Fiscal year** → **Create budget**. There is one budget per fiscal year.
2. Add allocations: account, cost centre (or **All cost centres**) and amount. Only budget-controlled accounts
   appear. The same account and cost centre twice, or a mix of account-level and cost-centre lines for one
   account, is refused. **Save allocations**, then **Submit**.
3. The finance manager approves.
4. On an approved budget, request an adjustment:
   * **From allocation**: an allocation, or "Supplemental (new money)";
   * **To allocation**, **Adjustment amount**, and an **Adjustment reason** of at least 10 characters;
   * then **Request**.

   A realignment can only move budget that is still available.

Under the production policy (BLOCK), expenditure beyond the available amount is refused. Budget definitions are
pending (C-10).

## 10. Reports

* **Financial statements**: choose a statement template and period; view it, or export it as XLSX, PDF or CSV.
  * Financial position and financial performance show balances.
  * The cash flow statement and the statement of changes in equity show the year-to-date movements, classified by
    the template.
  * A red box lists anything a template leaves out: unmapped accounts, or unclassified movements.
  * **BALANCED** confirms the template's check, for example that opening cash plus the year's net cash flow
    equals closing cash.
  * Statement formats are [PLACEHOLDER] until C-07.
* **My reports**: background report jobs. Choose a report and its parameters, then queue it; download it when
  ready. Reports include:
  * the trial balance export;
  * general and subsidiary ledgers;
  * AP and AR aging;
  * the Cash Disbursement Journal;
  * the collection summary and daily collection report;
  * the bank reconciliation statement;
  * the expanded withholding schedule;
  * PPE schedule, inventory valuation, budget versus actual;
  * the audit trail export (auditors).

  **Scheduled reports** repeat a report on a schedule (for example the previous month, month to date). Each
  report needs its own view permission.

## 11. Tickets (support)

Every internal user can raise and follow their own tickets.

1. **Tickets** → **New ticket**. Choose the type:
   * incident;
   * service request;
   * change request;
   * enhancement.
2. Choose the **Priority**, which drives the SLA:
   * P1, operations stopped;
   * P2, major function impaired;
   * P3, workaround exists;
   * P4, cosmetic or question.
3. Choose the **Severity**, the business impact from S1 to S4, and the **Category**. Both use [PLACEHOLDER]
   wording (C-34).
4. Enter the **Title**, **Description** and optional **Module**; **Submit ticket**. Never put passwords or
   account numbers in a ticket.
5. Attach screenshots or files with **Attach file** until the ticket is closed.
6. Follow the history and add comments. Support may add internal notes you do not see.
7. When support resolves the ticket, check the fix:
   * **Confirm and close**, with an optional satisfaction rating from 1 to 5;
   * or **Reopen**, saying why, within 7 days.

   A resolution you neither confirm nor reopen closes automatically after 7 days, marked unconfirmed.

**Change requests and enhancements.**
1. Support submits the change for approval with a **Rollback plan**.
2. A finance manager (`TICKET_APPROVE`) approves; never the requester or the assignee.
3. After the work, the requester or an approver chooses **Validate on staging** and records what was tested.
4. Support resolves it with **Release notes** and an optional release reference.

SLA timers pause while a ticket is on hold or awaiting approval. The SLA values are [PLACEHOLDER] (C-15).

## 12. Migration and parallel run (cutover only)

* **Opening balances** (`MIGRATION_PREPARE` uploads; `MIGRATION_APPROVE` applies):
  1. Upload a CAS-template CSV for a batch type, for example the GL opening trial balance or unpaid supplier
     invoices; choose **Upload and validate**.
  2. Fix any **Rows with problems** and upload again. The GL trial balance becomes an opening journal voucher
     (**Create opening voucher**) that goes through the normal workflow.
  3. Open-item batches must reconcile per party with the posted opening entry. A second person chooses
     **Apply batch**; a file can be applied only once.
* **Parallel run**:
  1. Upload the legacy trial balance for a period (**Compare**).
  2. Explain every difference, then **Sign off**. Sign-off is by a second person, and only when nothing is
     unexplained.
  3. **Compare again with CAS** after corrections; an explanation is kept only while its difference is unchanged.

## 13. Auditors

Internal and external auditors can view every financial area, the **Audit trail** and reports, but change
nothing. On the **Audit trail**:
* choose the **Event trail** (audit or security);
* filter by action, actor, resource and date;
* export through **My reports**;
* **Verify integrity** to recompute both hash chains.

External-auditor accounts are deactivated by an administrator when the engagement ends (an automatic expiry date exists but cannot yet be set from the screens).

## 14. Concessionaire portal

For concessionaire users linked to their company by MIAA.
* **Home**: account summary, open invoices and recent payments; **Download PDF** of a billing document.
* **Billing** and **Payments**: your posted billings and receipts only. You never see other companies' data.
* **Inquiries**: **New inquiry** with a **Subject** and message, or report a payment with the
  **Amount paid (₱)**, **Payment date** and **Bank or check reference**; **Send**.

  MIAA staff answer in CAS. A reported payment is not a receipt until MIAA records it.
* Portal accounts are linked by MIAA after checking your identity and authority; there is no self-registration.
