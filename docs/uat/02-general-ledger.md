# UAT: general ledger and journal vouchers

Placeholder accounts (`PLC-` codes) are used until AVTECH's approved chart is loaded (C-22). Account codes in the
steps are examples; use the UAT chart's equivalents.

### UAT-GL-001 Maintaining the chart of accounts
- RTM: FR-GL-01
- Roles: user with ACCOUNT_ADMINISTER

| Step | Action | Expected result |
|---|---|---|
| 1 | Chart of accounts: add a postable account under an existing header with type, normal balance, statement line, and "requires cost centre" | Saved; it appears under its parent |
| 2 | Add a child under a postable account, or a duplicate code | Refused with the reason |
| 3 | Set an effective-to date and deactivate the account | Vouchers dated after it cannot use the account (checked in UAT-GL-005) |
| 4 | Audit trail: find the changes | Each change is recorded with old and new values |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-002 Importing the approved chart, all or nothing
- RTM: FR-GL-02
- Roles: user with ACCOUNT_ADMINISTER
- Preconditions: a CSV of the approved chart (or a test extract) with one deliberate duplicate code

| Step | Action | Expected result |
|---|---|---|
| 1 | Import the file with the duplicate | Rejected with the line numbers; no account was created |
| 2 | Fix the file and import again | All accounts created, parents before children |
| 3 | On a production-configured environment, try to post to a placeholder (PLC-) account | Refused: placeholder accounts cannot be used there |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-003 Fiscal years, periods and closing a period
- RTM: FR-GL-03, FR-GL-04
- Roles: Chief Accountant (period control)

| Step | Action | Expected result |
|---|---|---|
| 1 | Fiscal periods: create the next fiscal year | 12 monthly periods plus an adjustment period, all FUTURE |
| 2 | Open period 1; soft-close it | While soft-closed only adjusting and closing vouchers can post |
| 3 | Open the close checklist with an unposted voucher still in the period | The checklist lists it; closing is refused until it is resolved or waived with a justification |
| 4 | Waive with a justification and close | The period is CLOSED; any posting dated in it is refused |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-004 Reopening a closed period needs a second person
- RTM: FR-GL-05
- Roles: two users with period-control permissions

| Step | Action | Expected result |
|---|---|---|
| 1 | User A: request reopening of the closed period with a reason | A pending request; the period stays closed |
| 2 | User A: try to approve the own request | Refused |
| 3 | User B: approve | The period reopens; request, decision and reason are audited |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-005 A journal voucher from draft to posting
- RTM: FR-GL-06, FR-DOC-01
- Roles: Accountant (preparer), Accountant (reviewer), Chief Accountant (approver), Chief Accountant (poster): four different people

| Step | Action | Expected result |
|---|---|---|
| 1 | Preparer: Journal vouchers > New; a balanced two-line voucher (one line needing a cost centre); save draft | Draft saved; the totals show balanced |
| 2 | Submit without a supporting document | Refused: a supporting document is required |
| 3 | Attach a PDF; submit | Status SUBMITTED; documents can no longer be changed |
| 4 | Reviewer: Approvals > the voucher > approve with a comment | Moves to the approval step |
| 5 | Approver: approve | Status APPROVED |
| 6 | Poster: post | Status POSTED with a JEV number in sequence; the entry appears in the trial balance |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-006 Nobody acts twice on their own voucher
- RTM: FR-GL-07
- Roles: the preparer and approver of UAT-GL-005

| Step | Action | Expected result |
|---|---|---|
| 1 | Preparer: try to review, approve or post their own voucher | Refused at each step |
| 2 | Reviewer of step 4 in UAT-GL-005: try to also approve the next step | Refused: one person per step |
| 3 | Approver: try to post the voucher they approved | Refused |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-007 Double entry, and posted entries never change
- RTM: FR-GL-08
- Roles: Accountant, Chief Accountant

| Step | Action | Expected result |
|---|---|---|
| 1 | Try to submit a voucher whose debits and credits differ | Refused with both totals |
| 2 | Open a posted entry and look for a way to edit or delete it | None exists; corrections are reversals (UAT-GL-009) |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-008 A voucher is posted once, and numbers have no gaps
- RTM: FR-GL-09
- Roles: Chief Accountant (poster)

| Step | Action | Expected result |
|---|---|---|
| 1 | Post an approved voucher; quickly press Post again, or post it from a second browser | Exactly one entry exists |
| 2 | List the entries of the period | JEV numbers run without gaps or duplicates |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-009 Reversing a posted entry
- RTM: FR-GL-10
- Roles: Accountant (preparer), approvers and poster as in UAT-GL-005

| Step | Action | Expected result |
|---|---|---|
| 1 | Open a posted entry; create its reversal with a date and reason | A draft reversal voucher with every line mirrored |
| 2 | Take it through approval and posting | Posted; the original entry is unchanged and shows it was reversed |
| 3 | Try to reverse the same entry again | Refused: one reversal per entry |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-010 Trial balance, account ledger and drill-down
- RTM: FR-GL-11
- Roles: any user with LEDGER_VIEW

| Step | Action | Expected result |
|---|---|---|
| 1 | Trial balance for the current period | Opening, movement and closing per account; totals balance |
| 2 | Account ledger for a cash account over a date range | Opening balance, each entry, running balance |
| 3 | Open an entry from the ledger, then its voucher and its document | Drill-down works at each level; the document downloads (and the download is audited) |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-011 Subsidiary ledgers agree with their control accounts
- RTM: FR-GL-12
- Roles: Accountant
- Preconditions: posted supplier and customer transactions (UAT-AP-002, UAT-AR-002)

| Step | Action | Expected result |
|---|---|---|
| 1 | Post a voucher to a control account without a subsidiary account | Refused: the account requires one |
| 2 | Open the subsidiary-ledger reconciliation | Per control account, the sum of subsidiary balances equals the account; differences are listed |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-012 Recurring journals create drafts that still need approval
- RTM: FR-GL-13
- Roles: user with RECURRING_JOURNAL_MANAGE; approvers

| Step | Action | Expected result |
|---|---|---|
| 1 | Recurring journals: define a monthly template; run what is due | A draft voucher is generated for the period |
| 2 | Pause the template; run again | Nothing is generated |
| 3 | Take the generated draft through approval | It needs the same approvals and segregation as any voucher |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-013 Year-end closing
- RTM: FR-GL-14
- Roles: Chief Accountant; approvers
- Preconditions: a UAT fiscal year with income and expense balances

| Step | Action | Expected result |
|---|---|---|
| 1 | Generate the closing voucher for the year, choosing the accumulated-surplus account | A voucher in the adjustment period closing every income and expense account |
| 2 | Approve and post it; close the fiscal year | Income and expense accounts are zero; the year is CLOSED; nothing more can post to it |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-014 Opening-balance vouchers belong to the first period
- RTM: FR-GL-16
- Roles: Accountant

| Step | Action | Expected result |
|---|---|---|
| 1 | Create an OPENING voucher dated in period 2 and submit it | Refused: opening balances belong to the first period of a year |
| 2 | Date it in period 1 | Accepted |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-GL-015 Returning, rejecting and cancelling vouchers
- RTM: FR-GL-06
- Roles: preparer and reviewer

| Step | Action | Expected result |
|---|---|---|
| 1 | Reviewer: return a submitted voucher with a comment | Back to the preparer as RETURNED, editable, with the comment shown |
| 2 | Preparer: correct and resubmit; reviewer rejects it with a reason | Status REJECTED; it can no longer be posted |
| 3 | Preparer: cancel another draft | Status CANCELLED; it stays visible in the list and the audit trail |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-DOC-001 Supporting documents are checked and kept
- RTM: FR-DOC-01
- Roles: Accountant

| Step | Action | Expected result |
|---|---|---|
| 1 | Attach an .exe renamed to .pdf | Refused: the content does not match the extension |
| 2 | Attach a file over 10 MB | Refused |
| 3 | Attach a valid PDF; note its SHA-256; download it | Download matches; the download is audited |
| 4 | After submission, try to remove or add a document | Refused: documents are frozen |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______
