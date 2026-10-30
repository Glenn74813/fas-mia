import type { ModuleHelp } from './types';

export const ACCOUNTING_HELP: Record<string, ModuleHelp> = {
  '/': {
    title: 'Dashboard',
    summary: 'Your own landing page. It shows only the sections your permissions allow, worked out by the server from posted records.',
    steps: [
      'Check Waiting for you first; each row shows a count and opens the list where the work is.',
      'Read the Cash section for cash in banks and on hand, collections not yet deposited and the book balance per bank account.',
      'Use the Receivables and Payables sections to see open balances, past-due amounts, ageing and the largest balances.',
      'Check the Budget section for allotment, reserved, actual and available amounts and the accounts with the highest utilisation.',
      'Look at Tickets for the tickets assigned to you.',
    ],
    roles: 'Every signed-in user has a dashboard; which sections appear depends on your role (for example Finance Manager, Chief Accountant or Management).',
    connections: [
      { to: '/inbox', how: 'Waiting for you counts the same documents that the Inbox lists.' },
      { to: '/receivables/billings', how: 'Receivables figures come from posted billings, receipts and applications.' },
      { to: '/payables/invoices', how: 'Payables figures come from approved supplier invoices and posted disbursements.' },
      { to: '/budgets', how: 'The Budget section summarises the approved budget for the fiscal year.' },
      { to: '/treasury/cash-position', how: 'Cash figures reflect bank book balances and undeposited collections.' },
    ],
  },

  '/inbox': {
    title: 'Inbox',
    summary: 'One list of every document across modules that is waiting for a step you are allowed to take, oldest first.',
    steps: [
      'Open the Inbox to see what is waiting; the list refreshes every minute.',
      'Click a queue chip to show one queue only, or All to see everything.',
      'Type a number, description, counterparty or preparer in Search and click Search to find a document.',
      'Click Open on a row to go to the document.',
      'Review it there and take the step (approve, return, post and so on); the Inbox itself does not act.',
    ],
    roles: 'You only see steps your role permits. Documents you prepared, and steps after one you already approved, are hidden (four-eyes rule).',
    connections: [
      { to: '/journal-vouchers', how: 'Vouchers waiting for review, approval or posting appear as Inbox items.' },
      { to: '/payables/invoices', how: 'Supplier invoices waiting for approval appear here.' },
      { to: '/payables/disbursements', how: 'Disbursement vouchers waiting for a step appear here.' },
      { to: '/close', how: 'Close tasks that fall to you appear here.' },
      { to: '/', how: 'The Dashboard shows the same items as counts in Waiting for you.' },
    ],
  },

  '/journal-vouchers': {
    title: 'Journal vouchers',
    summary: 'Prepare, route and post manual journal entries. Every ledger posting, manual or automatic, goes through a voucher.',
    steps: [
      'Click New voucher, choose the voucher type (Standard, Adjusting or Opening balances) and set the accounting date and description.',
      'Fill in the lines: account, cost centre and subsidiary where required, and a debit or credit. Total debits must equal total credits.',
      'Click Save draft, then use Attach file on the voucher to add supporting documents; manual vouchers need at least one (set per voucher type).',
      'Click Submit. The voucher is locked and goes to review, then approval, each by a different person.',
      'Once approved, a person with posting authority clicks Post, which creates the numbered journal entry.',
      'Reviewers and approvers can Return a voucher to the preparer or Reject it; the preparer can Edit a returned draft or Cancel it.',
      'To correct a posted entry, open the voucher and click Reverse; this creates a reversal voucher that goes through approval.',
    ],
    roles: 'Accountant prepares, submits and can review. Chief Accountant reviews, approves, posts and reverses. The preparer cannot review, approve or post their own voucher, and nobody takes two steps on the same voucher.',
    connections: [
      { to: '/general-ledger/entries', how: 'Posting creates an immutable journal entry; the voucher links to it.' },
      { to: '/general-ledger/trial-balance', how: 'Only posted entries count in the trial balance.' },
      { to: '/fiscal-periods', how: 'The accounting date decides the period; a soft-closed period accepts only adjusting and closing vouchers, and a closed period accepts none.' },
      { to: '/recurring-journals', how: 'Recurring templates generate draft vouchers for you to submit.' },
      { to: '/inbox', how: 'Vouchers waiting for review, approval or posting appear in the Inbox of those who can act.' },
      { to: '/general-ledger/allocations', how: 'Allocation runs create draft vouchers that follow the same route.' },
    ],
  },

  '/recurring-journals': {
    title: 'Recurring journals',
    summary: 'Templates for entries that repeat, such as monthly accruals. Each template generates draft vouchers on schedule.',
    steps: [
      'Click New template and enter a name, the voucher description and the frequency (Monthly, Quarterly or Annually).',
      'Set the day of month (1 to 28), the first run date and, if needed, an end date.',
      'Enter the template lines in the same way as on a journal voucher, then click Save template.',
      'Click Generate due vouchers to create drafts now; the system also does this daily at 01:00 (Manila).',
      'Use Pause or Resume in the list to stop or restart a template.',
      'Open each generated draft in Journal vouchers to submit it.',
    ],
    roles: 'Accountants maintain templates. Generated vouchers are drafts owned by the template owner and still need submission, review, approval and posting by different people.',
    connections: [
      { to: '/journal-vouchers', how: 'Each run creates a draft voucher that must go through the normal approval chain.' },
      { to: '/fiscal-periods', how: 'A generated voucher can only be posted into an open period (or a soft-closed one for adjusting types).' },
      { to: '/chart-of-accounts', how: 'Template lines use accounts from the chart and are validated again when the voucher is submitted.' },
    ],
  },

  '/general-ledger/trial-balance': {
    title: 'Trial balance',
    summary: 'Opening balance, period movement and closing balance of every account, computed from posted journal lines.',
    steps: [
      'Pick the period in Through period; the current period is chosen by default.',
      'Check the BALANCED or OUT OF BALANCE badge and the totals row.',
      'Watch for the USES PLACEHOLDER ACCOUNTS badge, which means postings exist on placeholder accounts.',
      'Click an account to open its Account ledger for that period.',
      'To export a trial balance, queue it in My reports.',
    ],
    roles: 'Anyone with ledger view access can read it, for example Accountant, Chief Accountant, Finance Manager and auditors.',
    connections: [
      { to: '/general-ledger/account-ledger', how: 'Each account row links to its ledger lines for the selected period.' },
      { to: '/journal-vouchers', how: 'Only vouchers that have been posted appear here.' },
      { to: '/fiscal-periods', how: 'Periods listed come from the fiscal calendar; future periods are not offered.' },
      { to: '/reports/financial-statements', how: 'The statements are built from the same posted balances, using the chart’s statement line codes.' },
      { to: '/reports/jobs', how: 'A trial balance export is produced as a report job.' },
    ],
  },

  '/general-ledger/account-ledger': {
    title: 'Account ledger',
    summary: 'Every posted line on one account for a date range, with a running balance.',
    steps: [
      'Choose an Account.',
      'Set the From and To dates; they default to the current calendar year.',
      'Read the opening balance, debits, credits and closing balance at the top.',
      'Click an entry number to open the posted journal entry.',
      'Use the page controls if the range has many lines.',
    ],
    roles: 'Available to users with ledger view access, such as Accountant, Chief Accountant, Finance Manager and auditors.',
    connections: [
      { to: '/general-ledger/trial-balance', how: 'The Trial balance links here with the account and period already filled in.' },
      { to: '/general-ledger/entries', how: 'Each line links to its journal entry.' },
      { to: '/chart-of-accounts', how: 'Only postable accounts from the chart can be chosen.' },
      { to: '/reports/ad-hoc', how: 'For other cuts of ledger data, build an ad hoc report.' },
    ],
  },

  '/general-ledger/entries': {
    title: 'Posted journal entry',
    summary: 'The permanent record of a posted voucher: header, lines and the people who prepared and posted it.',
    steps: [
      'Check the date, period, type, preparer and who posted it.',
      'Review the lines with their accounts, cost centres and subsidiary accounts, and the totals.',
      'Click Open voucher and documents to see the approval history and supporting files.',
      'Follow Reverses or Reversed by to move between an entry and its reversal.',
      'To correct the entry, reverse it from its voucher; the entry itself cannot be edited.',
    ],
    roles: 'Viewing needs ledger access. Reversal is done by a Chief Accountant, and the reversal voucher is approved and posted by other people.',
    connections: [
      { to: '/journal-vouchers', how: 'The entry links back to the voucher that created it, where reversal is started.' },
      { to: '/general-ledger/account-ledger', how: 'Entries appear as lines in the ledgers of the accounts they touch.' },
      { to: '/general-ledger/trial-balance', how: 'The entry’s lines are included in the trial balance of its period.' },
      { to: '/audit', how: 'Posting and reversal actions are recorded in the audit trail.' },
    ],
  },

  '/chart-of-accounts': {
    title: 'Chart of accounts',
    summary: 'The list of accounts that vouchers can post to, with type, normal balance, required dimensions and statement line.',
    steps: [
      'Use Filter by code or name to find an account.',
      'Read the Dimensions column: HEADER accounts are not postable, COST CENTRE means a cost centre is required, SL means a subsidiary account is required.',
      'Tick the inactive accounts checkbox to see accounts kept for their posted history.',
      'Look for PLACEHOLDER badges; these accounts belong to a development structure and are rejected where production configuration disallows them.',
      'Administrators click Import approved chart (CSV) to load the official chart; the import is all-or-nothing.',
    ],
    roles: 'Everyone with ledger access can read it. Chief Accountant maintains it. The role that maintains accounts is not normally the one that prepares vouchers.',
    connections: [
      { to: '/journal-vouchers', how: 'Voucher lines can only use active, postable accounts and must supply the required cost centre or subsidiary account.' },
      { to: '/finance-settings', how: 'Control-account mappings, tax codes and bank accounts point to accounts in this chart.' },
      { to: '/reports/financial-statements', how: 'The FS line code on each account decides where its balance appears on the statements.' },
      { to: '/general-ledger/open-items', how: 'Accounts can be flagged for open-item management and then cleared there.' },
      { to: '/budgets', how: 'Only accounts flagged as budget-controlled can receive an allotment.' },
    ],
  },

  '/general-ledger/open-items': {
    title: 'Open items',
    summary: 'On open-item managed accounts such as clearing or suspense accounts, lines stay open until they are matched and net to zero.',
    steps: [
      'Click an account under Managed accounts to see its open items and ageing.',
      'Tick two or more lines that net to zero, enter a Clearing note and click Clear selected.',
      'Or pick an Automatic clearing rule (by reference, or by amount) and click Preview, then Apply to clear the proposals.',
      'Review the Clearing history; click Reverse on a clearing, with a reason, to make its items open again.',
      'Administrators can add an account with Manage by open item, or remove it with Stop managing.',
    ],
    roles: 'Accountant and Chief Accountant can clear. Chief Accountant also chooses which accounts are managed by open item.',
    connections: [
      { to: '/journal-vouchers', how: 'Open items are posted lines from journal entries; clearing itself posts nothing.' },
      { to: '/chart-of-accounts', how: 'Eligible accounts come from the chart.' },
      { to: '/close', how: 'Uncleared open items are part of the period-end checks.' },
      { to: '/general-ledger/account-ledger', how: 'Use the account ledger to see the full movement behind an open item.' },
    ],
  },

  '/general-ledger/allocations': {
    title: 'Overhead allocation',
    summary: 'Cycles that move a period’s balance from a sending cost centre to receiving cost centres by percentage or quantity.',
    steps: [
      'Create a cycle with a code and name, then add one or more rules: sender account and cost centre, key type, and receivers with their percent or quantity.',
      'Click Create cycle; use Activate or Deactivate to control whether it can run.',
      'On a cycle, click Run or preview and choose a Period to see the proposed lines.',
      'Click Create draft voucher to run it. The voucher is dated the period’s last day.',
      'Open the run link to submit the draft through the normal approval route.',
    ],
    roles: 'Accountant and Chief Accountant manage cycles. Running needs voucher preparation rights too, so the draft is the runner’s own and must be reviewed, approved and posted by other people.',
    connections: [
      { to: '/journal-vouchers', how: 'A run creates a draft journal voucher that is reviewed and posted like any other.' },
      { to: '/chart-of-accounts', how: 'Sender and receiver accounts must be accounts the chart allows for allocation.' },
      { to: '/fiscal-periods', how: 'Only periods that are not in the future can be allocated.' },
      { to: '/general-ledger/trial-balance', how: 'After posting, the moved amounts show against the receiving cost centres.' },
    ],
  },

  '/fiscal-periods': {
    title: 'Fiscal periods',
    summary: 'The accounting calendar: fiscal years, their periods, and whether each period accepts postings.',
    steps: [
      'Enter a year and click Create fiscal year to set up its periods.',
      'Click Open on a future period when it is ready for postings.',
      'Click Soft-close to restrict an open period to adjusting and closing vouchers.',
      'Click Close… (or Checklist if you cannot close) to run the close checks; waive only waivable failures, with a justification.',
      'To post into a closed period, click Request reopen with a reason; a different user then Approves or Rejects it under Pending reopen requests.',
      'At year end, choose the accumulated surplus account, click Generate closing voucher, post it, then click Close year.',
    ],
    roles: 'Chief Accountant creates years, opens, soft-closes and closes periods. Finance Manager decides reopen requests. The requester cannot decide their own request.',
    connections: [
      { to: '/close', how: 'The Close calendar lists the checks and tasks that belong to month-end and year-end.' },
      { to: '/journal-vouchers', how: 'Voucher dates are tested against period status; the closing voucher is generated here and posted there.' },
      { to: '/general-ledger/trial-balance', how: 'Balances roll through the periods defined here.' },
      { to: '/reports/financial-statements', how: 'Statements are produced for a chosen period.' },
      { to: '/budgets', how: 'Each budget belongs to one fiscal year.' },
    ],
  },

  '/close': {
    title: 'Close calendar',
    summary: 'Checklists for day-end, month-end, quarter-end and year-end close: automatic checks plus tasks assigned to owners.',
    steps: [
      'Choose a checklist type tab, then pick the day, period, quarter or fiscal year.',
      'Read the Automatic checks; a failed check lists its exceptions.',
      'On a task you own, choose Done or Not applicable, add a note and click Complete task.',
      'Use the Closing schedule to see what falls due between two dates, and click an entry to open its checklist.',
      'For month-end, close the period itself on Fiscal periods.',
      'Authorised users can add or edit close tasks under Close tasks, including checklist, due days and owning permission.',
    ],
    roles: 'Each task is owned by a permission set on the task, so different roles complete different tasks. Chief Accountant manages the task list.',
    connections: [
      { to: '/fiscal-periods', how: 'Closing a period, with any waivers, is done there after the checklist is satisfied.' },
      { to: '/inbox', how: 'Open close tasks assigned to you show in your Inbox.' },
      { to: '/general-ledger/open-items', how: 'Open-item balances are among the automatic checks.' },
      { to: '/treasury/reconciliations', how: 'Unfinished bank reconciliations can hold up the close.' },
      { to: '/fixed-assets/depreciation', how: 'Depreciation for the period is a typical month-end task and check.' },
    ],
  },

  '/finance-settings': {
    title: 'Finance settings',
    summary: 'Configuration used by automatic postings: control-account mappings, tax codes and bank accounts.',
    steps: [
      'Under Control-account mappings, choose the ledger account for each key; a NOT CONFIGURED badge means no account is set.',
      'Under Tax codes, enter the code, type, rate, account and description, then click Add.',
      'Under Bank accounts, enter the code, bank, account name, last four digits and the cash GL account, then click Add.',
      'Check that no PLACEHOLDER tax codes remain before go-live.',
    ],
    roles: 'Chief Accountant maintains mappings and tax codes; bank accounts need bank account management rights. Others can view.',
    connections: [
      { to: '/chart-of-accounts', how: 'Every mapping, tax code and bank account points to an account in the chart.' },
      { to: '/payables/invoices', how: 'Invoice postings use the mapped control accounts and tax codes.' },
      { to: '/receivables/billings', how: 'Billing and receipt postings use the mapped receivable and revenue accounts.' },
      { to: '/treasury/statements', how: 'Bank accounts defined here are the ones statements and reconciliations are kept against.' },
    ],
  },

  '/budgets': {
    title: 'Budgets',
    summary: 'The corporate operating budget for each fiscal year: allotments per account and cost centre, tracked against reservations and actual spending.',
    steps: [
      'Choose a Fiscal year and click Create budget; years that already have a budget are not offered.',
      'Open the budget and, while it is editable, add allocations: account, cost centre (or all) and amount, then click Save allocations.',
      'Click Submit to send the budget for approval.',
      'An approver clicks Approve, or Return with a comment so the preparer can change it.',
      'Once approved, use Adjustments to request a supplemental amount or a transfer between allocations, with a reason of at least 10 characters.',
      'Approvers click Approve or Reject on each pending adjustment.',
    ],
    roles: 'Budget Officer prepares and requests adjustments. Finance Manager approves budgets and adjustments. Preparation and approval are separated.',
    connections: [
      { to: '/chart-of-accounts', how: 'Only accounts flagged as budget-controlled can be allotted.' },
      { to: '/payables/invoices', how: 'Supplier invoices reserve budget on budget-controlled accounts, and postings are checked against what is available.' },
      { to: '/journal-vouchers', how: 'Posted spending on a budgeted account counts as Actual.' },
      { to: '/', how: 'The Dashboard shows allotment, reserved, actual and the highest utilisation.' },
      { to: '/reports/jobs', how: 'Budget vs actual and Variance analysis can be requested or scheduled as reports.' },
    ],
  },

  '/reports/financial-statements': {
    title: 'Financial statements',
    summary: 'The statements of financial position, performance, cash flows and changes in equity, built from posted balances using versioned templates.',
    steps: [
      'Choose the Statement and the Period.',
      'Tick or clear Prior-year comparative.',
      'Read the statement. Check the BALANCED badge, and any red boxes for accounts or movements the template does not show.',
      'Heed the placeholder warning: a statement marked PLACEHOLDER is not official.',
      'Click Export XLSX, PDF or CSV; the file is prepared in My reports.',
      'Under Statement templates, a second administrator can Activate a newly defined version.',
    ],
    roles: 'Financial Reporting, Chief Accountant, Finance Manager and auditors can view; exporting needs export rights. A template is activated by someone other than its author.',
    connections: [
      { to: '/general-ledger/trial-balance', how: 'Both are computed from the same posted balances.' },
      { to: '/chart-of-accounts', how: 'Each account’s FS line code decides where it appears.' },
      { to: '/reports/jobs', how: 'Exports are queued as jobs and downloaded from My reports.' },
      { to: '/fiscal-periods', how: 'Only periods that are not in the future can be reported.' },
    ],
  },

  '/reports/ad-hoc': {
    title: 'Ad hoc reports',
    summary: 'Build your own report from an approved dataset by choosing columns, conditions, grouping and sorting.',
    steps: [
      'Choose a Dataset.',
      'Tick the Columns you want, in the order you want them.',
      'Add conditions with Add condition, and tick Group by columns to total the amounts.',
      'Choose Sort by, then click Run to preview; the screen shows the first 200 rows.',
      'Enter a Report name and click Save; tick Share to let colleagues who may see the same records use it.',
      'Click Export CSV, XLSX or PDF on a saved report; collect the file from My reports.',
    ],
    roles: 'You only get datasets and rows your role may see. Saved reports belong to their owner, who can change or delete them.',
    connections: [
      { to: '/reports/jobs', how: 'Exports are produced as report jobs and downloaded there.' },
      { to: '/general-ledger/account-ledger', how: 'For a single account’s running balance, the account ledger is quicker.' },
      { to: '/reports/financial-statements', how: 'Use the statements for official presentation; ad hoc reports are for analysis.' },
    ],
  },

  '/reports/jobs': {
    title: 'My reports',
    summary: 'Your report requests and schedules. Exports and management reports are prepared in the background and downloaded from here.',
    steps: [
      'Under Request a report, choose a report type, set its parameters and format, and queue it.',
      'To run it on a timetable, use the schedule option in the same panel (daily, weekly or monthly).',
      'Choose a period and format under Trial balance export and click Queue export.',
      'Watch Status in the list; it refreshes while jobs are queued or running.',
      'Click Download when the job is DONE; files are kept only until Available until.',
      'In Scheduled reports, switch E-mail when ready on or off, or Delete a schedule (reports already produced are kept).',
    ],
    roles: 'The report types offered depend on your permissions, for example ledger, payables, receivables, treasury, fixed assets, inventory or audit. Export rights are needed for financial exports.',
    connections: [
      { to: '/reports/financial-statements', how: 'Statement exports appear here once prepared.' },
      { to: '/reports/ad-hoc', how: 'Saved ad hoc reports are exported through this list.' },
      { to: '/general-ledger/trial-balance', how: 'Trial balance exports are requested here.' },
      { to: '/payables/reports', how: 'Payables reports such as AP ageing run as jobs.' },
      { to: '/receivables/reports', how: 'Receivables reports such as AR ageing and collection summary run as jobs.' },
      { to: '/audit', how: 'Every download is recorded in the audit trail with the file’s SHA-256.' },
    ],
  },
};
