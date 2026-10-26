# UAT: receivables, collections and the concessionaire portal

### UAT-AR-001 Customer master, contracts and contract billing
- RTM: FR-AR-01..14
- Roles: Accountant

| Step | Action | Expected result |
|---|---|---|
| 1 | Customers: create a concessionaire with TIN, credit limit and payment terms | Saved with its own subsidiary account |
| 2 | Add a contract with monthly amount, revenue account and VAT code | Saved; ACTIVE |
| 3 | Billing: run contract billing for the month | One draft invoice per active contract |
| 4 | Run it again for the same month | No second invoice is created for any contract |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AR-002 Invoices and credit memos
- RTM: FR-AR-01..14
- Roles: Accountant (prepares), Chief Accountant (approves), a second Chief Accountant (posts)

| Step | Action | Expected result |
|---|---|---|
| 1 | Billing: new invoice with output VAT; submit, approve, post (three people) | Posted: receivable debited to the customer, revenue and output VAT credited |
| 2 | Raise a credit memo against it with a justification document | Posted after approval; the invoice's open amount falls |
| 3 | Try a credit memo larger than what is still open | Refused |
| 4 | Bill above the customer's credit limit | The approver sees a warning (C-27) |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AR-003 Collections, tax withheld and unapplied amounts
- RTM: FR-AR-01..14
- Roles: Cashier (records), Chief Accountant (posts)

| Step | Action | Expected result |
|---|---|---|
| 1 | Collections: record a check with creditable withholding tax, allocated to an invoice, with a little extra | Receipt numbered; the extra is unapplied |
| 2 | Allocate more than the invoice's open amount | Refused |
| 3 | The Cashier tries to post; a Chief Accountant posts | Cashier refused; posted: cash on hand and withholding debited, receivable and unapplied credited |
| 4 | Later, apply the unapplied amount to another invoice | Posted; the customer's unapplied balance falls |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AR-004 Aging, statement of account and reconciliation
- RTM: FR-AR-01..14
- Roles: Accountant; Chief Accountant

| Step | Action | Expected result |
|---|---|---|
| 1 | Receivables reports: aging as of today | Open amounts per customer by age bucket; totals agree with open invoices |
| 2 | The customer's statement for a date range | Opening balance, invoices, memos, receipts and a running balance; unapplied amount shown |
| 3 | AR reconciliation | Open items equal the AR control account; unapplied collections equal their account |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-PORT-001 Linking a portal account needs a second person
- RTM: FR-PORTAL-01..07
- Roles: System Administrator (creates the account), Accountant (requests), Chief Accountant (approves)

| Step | Action | Expected result |
|---|---|---|
| 1 | Administrator: create a CONCESSIONAIRE user | The person can sign in but sees no customer data |
| 2 | Accountant: on the customer's page, request the link with evidence of identity and authority, attaching the authorisation letter | A pending request; the account still sees nothing |
| 3 | The Accountant tries to approve | Not offered or refused |
| 4 | Chief Accountant: Portal access > approve with a note | Linked; the user receives a notification |
| 5 | Unlink from the customer's page | Access ends at once |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-PORT-002 Concessionaires see only their own posted records
- RTM: FR-PORTAL-01..07
- Roles: two linked concessionaire users of different customers

| Step | Action | Expected result |
|---|---|---|
| 1 | Concessionaire A signs in | The portal shell (not the internal menu) with A's account summary |
| 2 | Open billings, one billing, payments and the statement | Only A's posted records; drafts never appear |
| 3 | Change the address bar to the ID of one of B's billings | "Not found"; nothing of B's is shown |
| 4 | Type an internal address such as /journal-vouchers | Access denied |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______
