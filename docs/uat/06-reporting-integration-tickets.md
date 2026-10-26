# UAT: reporting, integrations and tickets

Official statement layouts await C-07; the BIR EIS schema awaits C-06; SLA values await C-15. These scripts test the
mechanisms with the configured placeholders.

### UAT-RPT-001 Financial statements and their templates
- RTM: FR-RPT-01..19
- Roles: Financial Reporting (prepares a template), Chief Accountant (activates); any user with FINANCIAL_REPORT_VIEW

| Step | Action | Expected result |
|---|---|---|
| 1 | Financial statements: Statement of Financial Position for a period with prior-year comparative | Lines, totals and a balance check; accounts without a statement line are listed as unmapped |
| 2 | Create a new template version; its author tries to activate it | Refused: activation needs a second person |
| 3 | A Chief Accountant activates it | The new version is used; the old one is kept |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-RPT-002 Report jobs and exports
- RTM: FR-RPT-01..19
- Roles: Chief Accountant; a user without AR access

| Step | Action | Expected result |
|---|---|---|
| 1 | My reports: request the trial balance as XLSX, AR aging as PDF and budget vs actual as CSV | Each job is queued, runs in the background and becomes ready within 60 seconds |
| 2 | Download each | Files open; figures match the on-screen reports; downloads are audited |
| 3 | A user without AR access requests AR aging, or downloads someone else's report | Refused |
| 4 | Open the CSV in a spreadsheet | Text that starts with = + - @ is shown as text, never run as a formula |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-INT-001 The BIR EIS transmission log (simulated channel)
- RTM: FR-BIR-01..06
- Roles: Chief Accountant (queues), integration administrator (monitors)
- Preconditions: UAT runs the EIS channel in a non-production mode; nothing reaches BIR

| Step | Action | Expected result |
|---|---|---|
| 1 | Integrations: queue posted documents | Each document is queued once; queuing again adds nothing |
| 2 | Watch the transmissions | Every attempt is logged with time and result; failures back off and end as DEAD after the maximum attempts |
| 3 | Retry a DEAD transmission; cancel another | Retried or cancelled, both audited |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-TKT-001 An incident worked to closure within its SLA
- RTM: FR-TKT-01..08
- Roles: a requester; a support agent

| Step | Action | Expected result |
|---|---|---|
| 1 | Requester: Tickets > raise a P2 incident | Response and resolution due times from the active SLA policy |
| 2 | Agent: acknowledge, add an internal note, resolve | The note is hidden from the requester; times are recorded |
| 3 | Requester: close it | CLOSED; the history shows every step and cannot be edited |
| 4 | Let another ticket pass its due time | At risk, then breached; the escalation e-mail is sent; the breach stays on record |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-TKT-002 Change requests need independent approval; waiting pauses the clock
- RTM: FR-TKT-01..08
- Roles: requester, assignee, a third person with approval rights

| Step | Action | Expected result |
|---|---|---|
| 1 | Raise a change request; submit it for approval | AWAITING_APPROVAL; the SLA clock pauses |
| 2 | The requester or the assignee tries to approve | Refused |
| 3 | The third person approves | Work continues; the clock resumes |
| 4 | Put a ticket on hold, then resume it | Time on hold is not counted; the SLA compliance summary reflects it |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______
