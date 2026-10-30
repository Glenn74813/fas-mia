import type { ModuleHelp } from './types';

export const PAYABLES_RECEIVABLES_HELP: Record<string, ModuleHelp> = {
  '/payables/suppliers': {
    title: 'Suppliers',
    summary: 'The supplier master file: who MIAA buys from, their tax details and their bank accounts. Invoices and disbursement vouchers can only be raised against active suppliers.',
    steps: [
      'Search by code, name or TIN to see whether the supplier already exists.',
      'Click New supplier and fill in Code, Registered name, TIN and Address.',
      'Tick VAT-registered if the supplier charges VAT, and pick a Default withholding tax if one normally applies.',
      'Click Save supplier.',
      'Click Bank details on a supplier row to enter bank details for ADA payments (this opens the supplier bank page).',
      'On the bank page, fill in Bank, Branch, Account name and Account number, then click Save for verification.',
      'A second person checks the details independently (for example by calling the supplier), writes how they verified it in the note, and clicks Verify or Reject.',
    ],
    roles: 'Accountant maintains suppliers and enters bank details. Chief Accountant verifies bank details. The person who entered the bank details can never verify them. New or changed bank details withdraw the previous account at once and cannot be paid until verified. Only the last four digits of an account number are shown.',
    connections: [
      { to: '/payables/invoices', how: 'Supplier invoices are registered against a supplier; its VAT status and default withholding tax pre-fill the invoice.' },
      { to: '/payables/disbursements', how: 'A disbursement voucher pays one supplier, and ADA payments use the supplier’s verified bank account.' },
      { to: '/treasury/payment-files', how: 'Payment files only use bank accounts that a second person has verified.' },
      { to: '/payables/reports', how: 'Aging and the withholding schedule are grouped by supplier and use the supplier TIN.' },
    ],
  },

  '/payables/invoices': {
    title: 'Supplier invoices',
    summary: 'Register supplier invoices, route them for approval and post them as payables in the ledger. Only posted invoices with an open balance can be paid by a disbursement voucher.',
    steps: [
      'Click Register invoice.',
      'Choose the Supplier, enter the Supplier invoice number, the Invoice date, Due date and Accounting date, and a Description. Add the Request for Payment no. and RIAR no. if you have them.',
      'Add one line per expense or asset account with a Cost centre and Net amount. Pick a Withholding code on a line to override the supplier default, and pick Input VAT if the supplier is VAT-registered.',
      'Click Save draft. VAT and withholding are computed by the server from the configured tax codes.',
      'On the invoice page, attach the supplier invoice (required before submission), then click Submit.',
      'The approver reviews it and clicks Approve, Return or Reject. A banner warns if another invoice from the same supplier has the same amount within 7 days.',
      'After approval, a person with posting authority clicks Post. This creates the ledger entry.',
    ],
    roles: 'Accountant prepares and submits. Chief Accountant approves and posts. The preparer can never approve their own invoice. Whoever receives goods (Inventory) is blocked from approving the supplier’s invoice.',
    connections: [
      { to: '/payables/suppliers', how: 'Every invoice belongs to an active supplier from the supplier master file.' },
      { to: '/payables/disbursements', how: 'Posted invoices with an open balance are what a disbursement voucher allocates payment against.' },
      { to: '/journal-vouchers', how: 'Posting creates a SUBLEDGER journal entry (SL_AP_INVOICE): expense or asset, input VAT and withholding payable against accounts payable.' },
      { to: '/budgets', how: 'Approved invoices count against the budget allotment of their account and cost centre.' },
      { to: '/inbox', how: 'Submitted invoices waiting for approval or posting appear in the approver’s Inbox.' },
    ],
  },

  '/payables/disbursements': {
    title: 'Disbursement vouchers',
    summary: 'Pay supplier invoices. A disbursement voucher (DV) goes through budget certification, certification, approval, payment issue, release confirmation and posting.',
    steps: [
      'Click New DV, choose the Payee (supplier) and enter the Particulars. Add the Request for Payment no. if there is one.',
      'Enter a Pay now amount against each posted invoice with an open balance, or click full. Then click Save draft.',
      'On the DV page, attach the required documents and click Submit.',
      'The Budget Division clicks Certify budget, then the certifier clicks Certify, then the approver clicks Approve. Any of them can Return or Reject.',
      'Once approved, click Issue payment: choose Check or ADA / bank transfer, the bank account, the check number or ADA reference and the payment date. A second person clicks Approve check before a check is released.',
      'After the check is handed over or the transfer executed, click Confirm release with the release date. Use Void payment to undo an issued payment.',
      'An accountant with posting authority clicks Post, which records Dr accounts payable / Cr cash in bank.',
    ],
    roles: 'Disbursement Officer prepares and submits. Budget Officer certifies budget cover. Accountant certifies. Finance Manager approves the payment and approves the check. Cashier issues the payment and confirms release. Chief Accountant posts. Nobody can approve their own work, and the preparer cannot release the payment.',
    connections: [
      { to: '/payables/invoices', how: 'A DV pays posted supplier invoices and reduces their open balance.' },
      { to: '/payables/suppliers', how: 'The payee must be an active supplier; ADA payments use its verified bank account.' },
      { to: '/budgets', how: 'The DV shows the budget line position of what it pays for the Budget Division to certify.' },
      { to: '/treasury/payment-files', how: 'ADA / bank transfer payments are carried to the bank through payment files.' },
      { to: '/journal-vouchers', how: 'Posting creates a SUBLEDGER journal entry (SL_DV_PAYMENT): Dr accounts payable, Cr cash in bank.' },
    ],
  },

  '/payables/reports': {
    title: 'Payables reports',
    summary: 'Read-only payables reports: aging of what MIAA owes, the expanded withholding schedule, supplier advances and a check that the AP sub-ledger agrees with the general ledger.',
    steps: [
      'Change the As of date to see the Aging of payables by supplier, in Current, 1–30, 31–60, 61–90 and over 90 day columns.',
      'Pick a Year and Quarter to see the Expanded withholding schedule by payee and tax code, with monthly bases and tax withheld.',
      'Read the note shown above the schedule; it states the limits of the figures.',
      'If you can view the ledger, check the AP sub-ledger to general ledger box at the top: it says RECONCILED or shows the difference.',
      'Review Supplier advances, when there are any, against their control account.',
    ],
    roles: 'Anyone with payables view access can read these, including Accountant, Chief Accountant, Finance Manager, Cashier, Disbursement Officer and the auditors. The reconciliation box also needs ledger view access.',
    connections: [
      { to: '/payables/invoices', how: 'Aging is built from posted supplier invoices and their open balances.' },
      { to: '/payables/disbursements', how: 'Posted disbursement vouchers reduce the balances shown in aging.' },
      { to: '/general-ledger/trial-balance', how: 'The reconciliation compares the open invoices with the accounts payable control account in the ledger.' },
      { to: '/reports/financial-statements', how: 'Payables recorded here feed the liabilities in the financial statements through the ledger.' },
    ],
  },

  '/receivables/customers': {
    title: 'Customers',
    summary: 'The customer and concessionaire master file with contracts, credit terms, statement of account and portal access. Billing and collections are recorded against these customers.',
    steps: [
      'Search by code, name or TIN to find a customer, or click New customer.',
      'Fill in Code, Registered name, Type, TIN, Address, Billing e-mail, Credit limit (leave blank for none) and Payment terms (days), then click Save customer.',
      'Open a customer and click Add contract. Enter the Contract number, Start date, End date, Monthly amount (net of VAT), Output VAT, Revenue account and Cost centre.',
      'Use Suspend or Reactivate on a contract to stop or resume contract billing.',
      'Under Portal access, enter the Portal username and the Evidence of identity and authority, then click Request link. Attach the authorisation letter to the request.',
      'Use the Statement of account section to review the customer’s documents for a date range. Use Remove access to unlink a portal account immediately.',
    ],
    roles: 'Accountant maintains customers and contracts and requests portal links. Cashier cannot maintain customers. Chief Accountant approves portal links, and cannot approve one the same person requested.',
    connections: [
      { to: '/receivables/billings', how: 'Invoices and memos are raised against a customer; active contracts drive the monthly contract billing run.' },
      { to: '/receivables/receipts', how: 'Collections are recorded against a customer and applied to its open invoices.' },
      { to: '/receivables/portal-access', how: 'A portal link request made here waits for a different person to approve it on the portal access page.' },
      { to: '/portal', how: 'Once a link is approved, the concessionaire sees this customer’s posted billings, payments and statement in the portal.' },
    ],
  },

  '/receivables/billings': {
    title: 'Billing',
    summary: 'Prepare invoices, credit memos and debit memos for customers, route them for approval and post them to the ledger as receivables. Contract billing can create the monthly drafts for you.',
    steps: [
      'To bill from contracts, click Run contract billing, pick the Billing month and Invoice date, and click Generate drafts. It creates a draft invoice for each active contract not yet billed that month and is safe to re-run.',
      'To bill by hand, click New invoice or memo and choose the Kind (Invoice, Credit memo or Debit memo) and the Customer.',
      'For a credit memo, pick the Invoice to reduce; it must be a posted invoice or debit memo of the same customer. A debit memo can optionally name a related invoice of the same customer.',
      'Enter the Document date, Due date (blank uses the customer’s payment terms), Output VAT and Description, then add lines with Revenue account, Cost centre, Description and Net amount. Click Save draft.',
      'Attach documents on the billing page (a credit memo needs a justification before submission), then click Submit.',
      'The approver clicks Approve, Return or Reject. After approval, a person with posting authority clicks Post, which assigns the document number.',
    ],
    roles: 'Accountant prepares and submits. Chief Accountant approves and posts. The preparer can never approve their own billing. Cashiers cannot bill customers.',
    connections: [
      { to: '/receivables/customers', how: 'Billing uses the customer’s terms and, for contract billing, its active contracts.' },
      { to: '/receivables/receipts', how: 'Posted invoices and debit memos are the open items that collections are applied to.' },
      { to: '/journal-vouchers', how: 'Posting creates a SUBLEDGER journal entry (SL_AR_BILLING): Dr accounts receivable, Cr revenue and output VAT. A credit memo reverses this.' },
      { to: '/portal', how: 'Only posted billings are shown to the concessionaire in the portal.' },
      { to: '/receivables/reports', how: 'Open balances of posted billings feed the receivables aging.' },
    ],
  },

  '/receivables/receipts': {
    title: 'Collections',
    summary: 'Record money received from customers and apply it to their open invoices. Any part not applied is held as an unapplied collection until it is applied later.',
    steps: [
      'Click Record collection and choose the Customer.',
      'Enter the Receipt date, Payment method (Cash, Check or Bank transfer), the Check number / bank or Reference, and the Amount received.',
      'Enter Tax withheld by customer if they withheld tax; this should be backed by their withholding certificate.',
      'Enter an amount against each open invoice, or click full. Whatever you leave unapplied stays as an unapplied collection.',
      'Click Save receipt. The receipt number is assigned on save and the receipt is RECORDED. Attach evidence such as the deposit slip or withholding certificate.',
      'Accounting clicks Post on the receipt page. Later, click Apply unapplied to apply the remaining amount to invoices.',
      'Click Cancel, with a reason, only for a receipt that is not yet posted. Posted receipts are corrected by reversal.',
    ],
    roles: 'Cashier records collections. Chief Accountant posts them, and can also cancel an unposted receipt. Cashiers are blocked from billing customers, maintaining customer records and preparing bank reconciliations.',
    connections: [
      { to: '/receivables/billings', how: 'Collections are applied to posted invoices and debit memos and reduce their open balance.' },
      { to: '/receivables/customers', how: 'Each receipt belongs to a customer, and unapplied collections show on the customer record.' },
      { to: '/treasury/deposits', how: 'Cash and checks received are banked through deposits.' },
      { to: '/journal-vouchers', how: 'Posting creates SL_AR_RECEIPT (cash, withholding tax receivable against receivables and unapplied collections); applying later creates SL_AR_APPLICATION.' },
      { to: '/receivables/inquiries', how: 'A payment a concessionaire reports in the portal only informs Finance; the cashier checks it and records the receipt here.' },
    ],
  },

  '/receivables/inquiries': {
    title: 'Portal inquiries',
    summary: 'Questions and payment notifications sent by concessionaires from the portal. Finance answers them here.',
    steps: [
      'Choose Open, Answered, Closed or All to filter the list. It opens on Open.',
      'Read the message, the billing it refers to and any attachments.',
      'For a payment reference, check the reported payment against the bank. Nothing is recorded by the inquiry.',
      'If the payment is genuine, record it under Collections.',
      'Type your answer in the reply box and click Send reply. The concessionaire is e-mailed.',
      'Click Close when the matter is finished.',
    ],
    roles: 'Accountant and Cashier can respond, as can anyone else holding the portal inquiry respond permission.',
    connections: [
      { to: '/portal', how: 'Inquiries are created by concessionaires in the portal’s Inquiries tab, and your replies appear there.' },
      { to: '/receivables/receipts', how: 'A reported payment is only information; the cashier records the real receipt under Collections.' },
      { to: '/receivables/customers', how: 'Each inquiry links to the customer it came from.' },
      { to: '/inbox', how: 'Open inquiries waiting for a reply appear in the Inbox of those who can respond.' },
    ],
  },

  '/receivables/portal-access': {
    title: 'Portal access requests',
    summary: 'Approve or reject requests to link a concessionaire’s portal account to a customer. A concessionaire sees a customer’s records only after a second person approves the link.',
    steps: [
      'Open a pending request and read the Evidence of identity and authority and any attached authorisation letter.',
      'Check that the portal username is the person the customer authorised.',
      'Write how you checked the evidence in the decision note (at least 5 characters).',
      'Click Approve access, or Reject.',
      'To remove access later, use Remove access on the customer page. It takes effect immediately.',
    ],
    roles: 'An Accountant requests the link from the customer page. Chief Accountant approves or rejects. The approver must be a different person from the requester. The portal username must be an existing CONCESSIONAIRE user, which a system administrator creates; self-registration does not exist.',
    connections: [
      { to: '/receivables/customers', how: 'Requests are made, and access is removed, on the customer’s page.' },
      { to: '/portal', how: 'After approval the concessionaire sees that customer’s posted billings, payments and statement in the portal.' },
      { to: '/admin/users', how: 'The CONCESSIONAIRE user account must exist before a link can be requested.' },
      { to: '/inbox', how: 'Pending link requests wait for the approver in the Inbox.' },
    ],
  },

  '/receivables/reports': {
    title: 'Receivables reports',
    summary: 'Read-only receivables reports: aging of what customers owe, and a check that the customer sub-ledger agrees with the general ledger control accounts.',
    steps: [
      'Change the As of date to see the Aging of receivables by customer, in Current, 1–30, 31–60, 61–90 and over 90 day columns.',
      'If you can view the ledger, read the Customer sub-ledger to general ledger box at the top. It compares open invoices with the AR control account, and unapplied receipts with the unapplied control account.',
      'A difference means something was posted to a control account outside receivables, for example by a manual journal voucher. Investigate it in the general ledger.',
    ],
    roles: 'Anyone with receivables view access can read these: Accountant, Chief Accountant, Finance Manager, Cashier, reporting staff and the auditors. The reconciliation box also needs ledger view access.',
    connections: [
      { to: '/receivables/billings', how: 'Aging is built from posted invoices and debit memos, less credit memos and applied collections.' },
      { to: '/receivables/receipts', how: 'Posted and applied collections reduce open balances; unapplied amounts are reconciled separately.' },
      { to: '/general-ledger/trial-balance', how: 'The reconciliation compares sub-ledger totals with the control account balances in the ledger.' },
      { to: '/reports/financial-statements', how: 'Receivables recorded here feed the financial statements through the ledger.' },
    ],
  },

  '/portal': {
    title: 'Concessionaire portal',
    summary: 'Your self-service account with MIAA. See what you owe, your posted billings and payments, your statement of account, and send questions or payment references to MIAA Finance.',
    steps: [
      'Read the summary at the top: Outstanding balance, Overdue and Unapplied payments, as of the date shown.',
      'Open Billings to see posted invoices and memos. Click a number to see its details.',
      'Open Payments to see payments MIAA Finance has posted, with tax withheld and any unapplied amount.',
      'Open Statement of account, choose From and To dates, and click Download PDF if you need a copy.',
      'Open Inquiries and click New inquiry. Choose a question or “I have made a payment”, add the billing if relevant, attach proof of payment (PDF or image) and click Send.',
      'MIAA Finance replies in the same tab and by e-mail.',
    ],
    roles: 'For concessionaires only. You see only the customer your account is linked to, and only posted documents. If you see a message that your account is not linked, contact MIAA Finance.',
    connections: [
      { to: '/receivables/billings', how: 'The Billings tab shows documents that MIAA has approved and posted.' },
      { to: '/receivables/receipts', how: 'A payment appears under Payments only after MIAA Finance has posted the receipt.' },
      { to: '/receivables/inquiries', how: 'Your inquiries and payment references go to MIAA Finance there to be answered.' },
      { to: '/receivables/portal-access', how: 'Your account is linked to your company only after a second MIAA staff member approves the request.' },
    ],
  },
};
