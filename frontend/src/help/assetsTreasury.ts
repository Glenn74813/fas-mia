import type { ModuleHelp } from './types';

export const ASSETS_TREASURY_HELP: Record<string, ModuleHelp> = {
  '/treasury/deposits': {
    title: 'Deposits',
    summary: 'Groups posted collections into a bank deposit slip and records the move from cash on hand to the bank account in the ledger.',
    steps: [
      'Click New deposit.',
      'Choose the Bank account, the Deposit date and enter the Deposit slip number.',
      'Tick the collections to include; the Deposit total updates as you go.',
      'Click Save deposit to open the deposit, then upload the validated deposit slip.',
      'A second person opens the deposit and clicks Post to record it in the general ledger.',
      'Click Cancel (with a reason) on a recorded deposit to release its collections for another deposit.',
    ],
    roles: 'Cashier prepares the deposit. Someone with posting authority, such as the Chief Accountant, posts it, so the preparer does not post their own deposit. A person who deposits cash cannot also reconcile the bank.',
    connections: [
      { to: '/receivables/receipts', how: 'Only collections already posted by accounting are offered for deposit; each deposit lists its receipts.' },
      { to: '/treasury/daily-collections', how: 'The daily collection report shows which deposit each receipt went into and what is not yet deposited.' },
      { to: '/general-ledger/entries', how: 'Posting a deposit creates a SUBLEDGER journal entry (SL_TR_DEPOSIT): debit cash in bank, credit cash on hand.' },
      { to: '/treasury/reconciliations', how: 'Posted deposits appear as book entries to match against the bank statement lines.' },
    ],
  },

  '/treasury/daily-collections': {
    title: 'Daily collection report',
    summary: 'Summarises the receipts issued on one day, by payment method and collector, with the amount not yet deposited.',
    steps: [
      'Pick the Date.',
      'Optionally type a Collector name to narrow the report; leave it blank for all collectors.',
      'Check the receipt number range, the issued and cancelled counts and the totals by payment method.',
      'Compare Total collected with Not yet deposited, and look at the Deposit column for each receipt.',
      'Click Print to produce a paper copy.',
    ],
    roles: 'Treasury viewers and collecting officers can open it. It is read-only.',
    connections: [
      { to: '/receivables/receipts', how: 'Every line is a receipt recorded in Receivables, including cancelled ones and tax withheld.' },
      { to: '/treasury/deposits', how: 'The Deposit column shows the deposit that took each receipt to the bank; blank means not yet deposited.' },
      { to: '/treasury/cash-position', how: 'Posted collections not yet deposited are also shown as cash on hand in the cash position.' },
    ],
  },

  '/treasury/statements': {
    title: 'Bank statements',
    summary: 'Imports bank statement files and starts the bank reconciliation for each statement.',
    steps: [
      'In Import a statement, choose the Bank account and the Format (camt.053, MT940 or the CSV layout).',
      'For CSV, enter the Opening balance and Closing balance and follow the column layout shown.',
      'Choose the Statement file and click Import.',
      'Find the statement in the list; Lines (unmatched) shows how many lines still need matching.',
      'Click Reconcile on the statement to open its bank reconciliation, or click the status badge to return to one already started.',
    ],
    roles: 'Accountant imports statements and prepares reconciliations. Finance Manager approves them.',
    connections: [
      { to: '/treasury/reconciliations', how: 'Each imported statement is reconciled against the ledger balance of its bank account.' },
      { to: '/finance-settings', how: 'Bank accounts are set up in finance settings; only active bank accounts can be chosen here.' },
      { to: '/treasury/cash-position', how: 'The cash position shows when each bank account was last reconciled.' },
    ],
  },

  '/treasury/reconciliations': {
    title: 'Bank reconciliation',
    summary: 'Matches bank statement lines to ledger entries for one bank account and statement date, and shows whether adjusted bank and book balances agree.',
    steps: [
      'Click Auto-match to let the system pair obvious lines.',
      'For the rest, tick bank lines and book entries whose totals are equal, then click Match selected.',
      'Click Undo beside a match you made by mistake; this is only possible before submission.',
      'Book bank charges or interest that are not yet in the ledger with a journal voucher, then match them.',
      'When the Difference is zero, click Submit; the figures are frozen and sent to the approver.',
      'The approver clicks Approve, or Return with a comment to send it back for correction.',
    ],
    roles: 'Accountant prepares and matches. Finance Manager approves; the preparer can never approve their own reconciliation. People who deposit cash or release payments cannot reconcile or approve.',
    connections: [
      { to: '/treasury/statements', how: 'A reconciliation is started from an imported bank statement.' },
      { to: '/journal-vouchers', how: 'Bank charges, interest and other unrecorded items are booked with a journal voucher and then matched.' },
      { to: '/general-ledger/entries', how: 'The book side lists posted ledger lines on the bank account, including deposits and payments.' },
      { to: '/treasury/cash-position', how: 'Checks are marked cleared when matched on an approved reconciliation.' },
      { to: '/close', how: 'An unapproved bank reconciliation can hold up period close.' },
    ],
  },

  '/treasury/payment-files': {
    title: 'ADA payment files',
    summary: 'Bundles approved disbursement vouchers into a bank payment file for an authority-to-debit-account (ADA) payment.',
    steps: [
      'Review Approved vouchers awaiting payment; a voucher with a blocked reason cannot be ticked.',
      'Tick the vouchers to pay and choose the Debit account and Payment date.',
      'Click Create payment file; this issues the ADA instruction for each voucher.',
      'Download the file from the batch list and upload it to the bank only where the bank requires it.',
      'Once the bank has processed the payments, confirm release on each disbursement voucher.',
    ],
    roles: 'Cashier creates and downloads payment files. Whoever releases payments cannot reconcile or approve the bank reconciliation. Others with treasury access can see the batch list.',
    connections: [
      { to: '/payables/disbursements', how: 'Only approved disbursement vouchers are listed; release is confirmed on the voucher afterwards.' },
      { to: '/payables/suppliers', how: 'Payee bank details come from the supplier record; a voucher whose supplier has no verified bank account cannot go into an ADA payment file.' },
      { to: '/treasury/statements', how: 'The bank statement later shows the payments so they can be matched in the reconciliation.' },
      { to: '/finance-settings', how: 'The debit account is one of the active bank accounts set up in finance settings.' },
    ],
  },

  '/treasury/cash-position': {
    title: 'Cash position and checks',
    summary: 'Shows book balances of each bank account and cash on hand as of a date, plus the register of issued checks.',
    steps: [
      'Pick the As of date.',
      'Read the book balance of each bank account, when it was last reconciled, and the Total in banks.',
      'Note Posted collections not yet deposited under the table.',
      'In the Check register, filter by Bank account and by status (Issued, Released, Cleared or Voided).',
      'Use the Age (days) column to spot checks that have been outstanding a long time.',
    ],
    roles: 'Read-only for anyone with treasury access.',
    connections: [
      { to: '/treasury/reconciliations', how: 'Balances are per the books; the bank figures are on the reconciliations, and an approved reconciliation clears matched checks.' },
      { to: '/payables/disbursements', how: 'The check register lists checks issued from disbursement vouchers.' },
      { to: '/treasury/deposits', how: 'Posted deposits move money from cash on hand to the bank balance shown here.' },
      { to: '/treasury/cash-forecast', how: 'The forecast starts from this cash balance.' },
    ],
  },

  '/treasury/cash-forecast': {
    title: 'Cash-flow forecast',
    summary: 'Projects the cash balance forward by week or month from today’s cash, expected collections and payments, and optionally unspent budget.',
    steps: [
      'Choose Group by week or by month, and the Horizon.',
      'Tick or untick Include budgeted spending not yet committed.',
      'Check Cash now, the Lowest projected balance and the overdue collections and payables.',
      'Look at the Projected closing balance chart and table; click a period to list the items expected in it.',
      'Click an item to open the billing, invoice or voucher behind it, and read the notes about the assumptions.',
    ],
    roles: 'Read-only for anyone with treasury access.',
    connections: [
      { to: '/receivables/billings', how: 'Open billings are expected as collections on their due dates.' },
      { to: '/payables/invoices', how: 'Supplier invoices are expected to be paid on their due dates.' },
      { to: '/payables/disbursements', how: 'Approved disbursement vouchers not yet paid are counted as payments.' },
      { to: '/budgets', how: 'Approved budget not yet used or reserved is spread over the rest of the year when budgeted spending is included.' },
      { to: '/treasury/cash-position', how: 'The opening balance is the book balance of banks and cash on hand.' },
    ],
  },

  '/fixed-assets': {
    title: 'Fixed assets',
    summary: 'The asset register. Register an asset, activate it, then transfer, impair, revalue or dispose of it; each step has its own approval and ledger entry.',
    steps: [
      'Search or filter the register by category and status, or click an asset number to open it.',
      'Click Register asset, choose the Category and fill in the description, dates, cost, residual value and useful life.',
      'Select the acquisition source: a posted supplier invoice, a completed AUC project, or Other with an offset account and justification; then click Save draft.',
      'A second person with posting authority opens the draft and clicks Activate, which assigns the asset number. Use Cancel to discard a draft.',
      'On an active asset, use the Transfers panel (Prepare transfer) to change cost centre, location or custodian; a different person clicks Post transfer.',
      'Use Impair (recoverable amount, PAS 36), Revalue (fair value, PAS 16) or Dispose (date, method, proceeds, reason); each is saved as a draft and posted by a second person from the asset page.',
    ],
    roles: 'Accountant registers and prepares transfers, impairments, revaluations and disposals. Chief Accountant (posting authority) activates and posts them. The preparer cannot post their own work. Depreciation must be posted up to the month before an impairment, revaluation or disposal.',
    connections: [
      { to: '/fixed-assets/setup', how: 'Categories supply default life, residual rate and the asset, accumulated depreciation and expense accounts; AUC projects are set up there.' },
      { to: '/payables/invoices', how: 'A purchased asset links to a posted supplier invoice already in the ledger, so activation posts nothing new.' },
      { to: '/fixed-assets/depreciation', how: 'Active assets are depreciated monthly; impairment, revaluation and disposal restart or stop depreciation.' },
      { to: '/general-ledger/entries', how: 'Posting creates SUBLEDGER entries: SL_FA_ACQUISITION, SL_FA_TRANSFER, SL_FA_REMEASUREMENT and SL_FA_DISPOSAL.' },
      { to: '/inbox', how: 'Transfers and other drafts waiting for a second person appear in that person’s inbox.' },
    ],
  },

  '/fixed-assets/depreciation': {
    title: 'Depreciation runs',
    summary: 'Computes and posts straight-line monthly depreciation for all active assets in an open fiscal period.',
    steps: [
      'Select an open period.',
      'Click Compute depreciation to prepare a run; months not yet charged are caught up and nothing is charged twice.',
      'Open the run and review the amount listed for each asset.',
      'A second person clicks Post to record the depreciation, or Cancel to discard the run and compute a new one.',
    ],
    roles: 'Accountant computes the run. Chief Accountant (posting authority) posts it; the preparer does not post their own run.',
    connections: [
      { to: '/fixed-assets', how: 'Each asset’s accumulated depreciation and net book value are updated and shown in its depreciation history.' },
      { to: '/general-ledger/entries', how: 'Posting creates a SUBLEDGER entry (SL_FA_DEPRECIATION): debit depreciation expense, credit accumulated depreciation.' },
      { to: '/fiscal-periods', how: 'Runs are computed for an open fiscal period.' },
      { to: '/close', how: 'The period close checks that depreciation for the period has been posted.' },
    ],
  },

  '/fixed-assets/setup': {
    title: 'Asset setup',
    summary: 'Maintains asset categories with their default life, residual rate and ledger accounts, and assets under construction (AUC) projects.',
    steps: [
      'Review the Asset categories table; rows marked PLACEHOLDER need to be confirmed against MIAA’s accounting policy.',
      'To add a category, enter the Category code, name, Default life (months) and Default residual rate, choose the asset, accumulated depreciation and expense accounts, and click Add category.',
      'Set the accumulated impairment account of a category before impairing assets in it.',
      'To track construction, enter a Project code and name, choose the AUC account and click Open project.',
      'Post project costs to the AUC account with the project’s subsidiary account, then register the finished asset from the project. Click Close when the project is complete.',
    ],
    roles: 'Accountant maintains categories and projects.',
    connections: [
      { to: '/chart-of-accounts', how: 'Category and AUC project accounts are chosen from the chart of accounts.' },
      { to: '/fixed-assets', how: 'New assets take their default life, residual value and accounts from the category chosen.' },
      { to: '/payables/invoices', how: 'Invoices for construction costs are charged to the AUC account to build up a project balance.' },
      { to: '/journal-vouchers', how: 'Costs can also be moved to an AUC project with a journal voucher.' },
    ],
  },

  '/fixed-assets/reports': {
    title: 'PPE reports',
    summary: 'Reconciles the asset register to the general ledger and shows the property, plant and equipment schedule for a date range.',
    steps: [
      'Read Asset register to general ledger and check for the RECONCILED or DIFFERENCE badge.',
      'If there is a difference, compare register and ledger balances per account to find it.',
      'Set the date range for the PPE schedule.',
      'Review the movements by category and read the note under the schedule.',
    ],
    roles: 'Read-only for anyone who can view fixed assets.',
    connections: [
      { to: '/general-ledger/trial-balance', how: 'Register totals are compared with the ledger balances of the asset and accumulated depreciation accounts.' },
      { to: '/fixed-assets', how: 'A difference usually means a manual voucher to an asset account, or a purchased asset not yet registered.' },
      { to: '/reports/financial-statements', how: 'The PPE schedule supports the property, plant and equipment line in the statements.' },
    ],
  },

  '/inventory/items': {
    title: 'Stock on hand',
    summary: 'Lists inventory items with quantity, average cost and value by warehouse, and maintains items and warehouses.',
    steps: [
      'Browse the list; click an item to see its stock by warehouse and its movements.',
      'To add an item, enter the Item code, Item name and Unit of measure, choose the inventory and consumption accounts, and click add.',
      'To add a storage location, enter a Warehouse code and name and click Add warehouse.',
      'Change stock quantities through stock documents, not on this page.',
    ],
    roles: 'Inventory Clerk maintains items and warehouses. Accountants, Chief Accountant and Finance Manager can view.',
    connections: [
      { to: '/inventory/documents', how: 'Receipts, issues, counts and transfers change quantities and average cost.' },
      { to: '/chart-of-accounts', how: 'Each item is linked to an inventory account and a consumption account from the chart of accounts.' },
      { to: '/inventory/reports', how: 'Stock values are reconciled to the general ledger in the inventory reports.' },
    ],
  },

  '/inventory/documents': {
    title: 'Stock documents',
    summary: 'Records goods receipts, issues, stock counts, transfers and price adjustments. Posting updates stock and the ledger.',
    steps: [
      'Click New document and choose the Document kind (goods receipt, issue, stock count, transfer or price adjustment).',
      'Fill in the date and description, plus the Supplier for a receipt or the requesting cost centre for an issue.',
      'Add lines: item, warehouse, quantity, and unit cost where asked.',
      'Click Save draft. To load many lines from a CSV file instead, use Import lines from a CSV file and click Import as draft.',
      'A second person opens the draft and clicks Post. Click Cancel to discard a draft.',
    ],
    roles: 'Inventory Clerk prepares documents. Someone else with posting authority, such as the Chief Accountant, posts them; the preparer does not post their own. Anyone who receives goods cannot approve the supplier’s invoice or release payments.',
    connections: [
      { to: '/inventory/items', how: 'Posting moves quantities and updates the weighted average cost of items.' },
      { to: '/payables/suppliers', how: 'A goods receipt names the supplier the goods came from.' },
      { to: '/payables/invoices', how: 'Receipts accrue to GR/IR clearing, which is cleared when the supplier invoice is matched.' },
      { to: '/general-ledger/entries', how: 'Posting creates a SUBLEDGER entry with an SL_INV_ source type for the document kind.' },
      { to: '/inventory/nrv-reviews', how: 'A proposed write-down from a cost vs market review arrives here as a draft price adjustment.' },
    ],
  },

  '/inventory/reports': {
    title: 'Inventory reports',
    summary: 'Reconciles stock values and GR/IR clearing to the general ledger and lets you match goods receipts to invoices.',
    steps: [
      'Check Inventory and GR/IR to general ledger for the RECONCILED or DIFFERENCE badge.',
      'Review GR/IR clearing: goods received but not invoiced, and invoices charged to GR/IR but not matched to a receipt.',
      'To match, choose the receipt and the invoice, enter the Amount to match and click Match.',
    ],
    roles: 'Read-only for anyone who can view inventory. The reconciliation also needs ledger access. Matching needs permission to prepare payables invoices.',
    connections: [
      { to: '/inventory/documents', how: 'Posted receipts and issues make up the stock value being reconciled.' },
      { to: '/payables/invoices', how: 'Supplier invoices charged to GR/IR are matched against open goods receipts.' },
      { to: '/general-ledger/trial-balance', how: 'Stock value and GR/IR are compared with the ledger balances of their accounts.' },
    ],
  },

  '/inventory/nrv-reviews': {
    title: 'Cost vs market review',
    summary: 'The year-end review of inventory at the lower of cost and net realisable value, with slow-moving flags. The basis and thresholds are proposals pending MIAA’s accounting policy.',
    steps: [
      'Choose the Review date and click Start review.',
      'Click Open on the review to see each item’s quantity, average cost, issues in the last twelve months and months of stock.',
      'Enter the market value per unit for each item, or click Import prices (CSV), then click Save prices.',
      'Where market value is below cost the write-down is calculated; click Propose write-down.',
      'Open the draft price adjustment under Stock documents; a second person reviews and posts it.',
      'Click Cancel review to discard a review you no longer need.',
    ],
    roles: 'Inventory Clerk starts the review and proposes the write-down. Someone else with posting authority posts the price adjustment.',
    connections: [
      { to: '/inventory/items', how: 'The review reads each item’s quantity and average cost on the review date.' },
      { to: '/inventory/documents', how: 'Propose write-down creates a draft price adjustment document that is posted like any stock document.' },
      { to: '/close', how: 'It is part of the year-end work before closing the books.' },
    ],
  },
};
