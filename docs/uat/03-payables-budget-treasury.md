# UAT: payables, budget and treasury

Role assignments follow the proposed matrix (C-08); tax codes and rates are placeholders until confirmed.

### UAT-AP-001 Supplier master and verified bank details
- RTM: FR-AP-01..15
- Roles: Accountant (maintains suppliers), Chief Accountant (verifies bank details)

| Step | Action | Expected result |
|---|---|---|
| 1 | Suppliers: create a supplier with TIN, VAT status and default withholding code | Saved with its own subsidiary account |
| 2 | Enter the supplier's bank account | Only the last four digits are shown afterwards; status PENDING |
| 3 | The same Accountant tries to verify the bank details | Refused: verification needs a second person |
| 4 | Chief Accountant: verify with a note about the call-back | Status VERIFIED; payment files may now use it |
| 5 | Replace the bank account | The old one is withdrawn at once; the new one is PENDING until verified |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AP-002 A supplier invoice from registration to posting
- RTM: FR-AP-01..15, FR-BUD-01..11
- Roles: Accountant (preparer), Chief Accountant (approver), a second Chief Accountant (poster)

| Step | Action | Expected result |
|---|---|---|
| 1 | Supplier invoices: register an invoice with an input-VAT code and an expense line with a cost centre; attach the invoice | Net, VAT, withholding and payable amounts are computed |
| 2 | Register the same supplier and invoice number again | Refused as a duplicate |
| 3 | Submit; the preparer tries to approve | Refused |
| 4 | Approver approves; the approver tries to post | Refused |
| 5 | Poster posts | Posted: expense and input VAT debited, withholding and payable credited to the supplier's subsidiary account |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AP-003 Paying invoices through a disbursement voucher
- RTM: FR-AP-01..15, FR-TRY-01..12
- Roles: Disbursement Officer (prepares), Accountant (certifies), Finance Manager (approves), Cashier (releases), Chief Accountant (posts)

| Step | Action | Expected result |
|---|---|---|
| 1 | Disbursement vouchers: new DV for the posted invoice; attach documents; submit | DV numbered; the invoice's open amount is committed |
| 2 | Accountant certifies; Finance Manager approves | Each step by a different person; the preparer cannot act on any step |
| 3 | Cashier: issue a check (or ADA instruction) and confirm release | Check number and release recorded |
| 4 | Chief Accountant: post | The supplier's payable is debited and cash credited; the invoice shows as paid |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AP-004 Payables reports
- RTM: FR-AP-01..15
- Roles: Accountant; Chief Accountant (reconciliation needs ledger access)

| Step | Action | Expected result |
|---|---|---|
| 1 | Payables reports: aging as of today | Open invoices by supplier in age buckets; totals match the open invoices |
| 2 | AP sub-ledger to general ledger | Open invoices equal the AP control account, or the difference is shown |
| 3 | Expanded withholding schedule for the quarter | Base and tax withheld per supplier and tax code, by month (the BIR 2307 printable layout awaits C-06) |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-BUD-001 Budgets, reservation and over-budget control
- RTM: FR-BUD-01..11
- Roles: Budget Officer (prepares), Finance Manager (approves), Accountant (spends)

| Step | Action | Expected result |
|---|---|---|
| 1 | Budgets: prepare the year's budget with allocations per expense account and cost centre; submit | DRAFT, then SUBMITTED |
| 2 | Finance Manager approves; the Budget Officer cannot | Status APPROVED; allotments are available |
| 3 | Submit a supplier invoice against an allocation | The amount is reserved; available balance falls |
| 4 | Submit an invoice exceeding what is left | Blocked or warned according to the configured enforcement (C-10) |
| 5 | Post the first invoice | The reservation becomes actual; utilisation shows reserved, actual and available |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-BUD-002 Budget adjustments and variance
- RTM: FR-BUD-01..11
- Roles: Budget Officer, Finance Manager

| Step | Action | Expected result |
|---|---|---|
| 1 | Request a transfer between two allocations with a justification | Pending; nothing moves yet |
| 2 | Finance Manager approves | Both allocations change; the history shows the adjustment |
| 3 | Budget vs actual report for the year | Allotment, reserved, actual, available and variance per allocation |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-TRY-001 Deposits and the daily collection report
- RTM: FR-TRY-01..12
- Roles: Cashier (records and deposits), Chief Accountant (posts)
- Preconditions: posted collections (UAT-AR-003)

| Step | Action | Expected result |
|---|---|---|
| 1 | Daily collections for the day | Every receipt by method with totals; cancelled receipts shown as such |
| 2 | Deposits: deposit the day's collections into the bank account | Each collection can be deposited once; a second deposit of the same receipt is refused |
| 3 | A second person posts the deposit | Cash on hand credited, bank debited; cash position shows nothing undeposited |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-TRY-002 Bank statement import and reconciliation
- RTM: FR-TRY-01..12
- Roles: Accountant (imports and prepares), Finance Manager (approves)
- Preconditions: a statement file (camt.053, MT940 or the CAS CSV) for the UAT bank account

| Step | Action | Expected result |
|---|---|---|
| 1 | Bank statements: import the file | Lines imported; a file whose balances do not add up, for another account, or overlapping a previous one is refused |
| 2 | Reconcile: auto-match, then match the rest by hand | Matched lines and outstanding items listed; the difference is shown |
| 3 | Submit with a non-zero difference | Refused |
| 4 | At zero difference, submit; Finance Manager approves | Approved; matches are frozen |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-TRY-003 Payment files for approved vouchers
- RTM: FR-TRY-01..12, FR-AP-01..15
- Roles: Cashier
- Preconditions: an approved DV for a supplier with VERIFIED bank details

| Step | Action | Expected result |
|---|---|---|
| 1 | Payment files: select the voucher and the debit account; create the file | A numbered file; the voucher's ADA instruction is issued |
| 2 | Download it | The CAS generic CSV (not a bank's layout, C-11) with payee, account, amount and reference |
| 3 | Try to include the same voucher in another file | Not offered |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______
