# CAS Administrator Manual

For system administrators (users, roles, organisation, integrations, tickets) and finance administrators
(chief accountant and finance manager: chart of accounts, settings, close and approval configuration). Version for
schema V41, October 2026. Installation and releases are in [19-deployment-and-rollback.md](../19-deployment-and-rollback.md).

**Separation of duties applies to administrators too.**
* System administrators hold no financial permissions. Rules block combining `USER_ADMINISTER` or
  `ROLE_ADMINISTER` with any financial permission ([05](../05-security-design.md) §5).
* Administrators cannot change their own roles, deactivate themselves or reset their own MFA.
* Every administrative change is audited.

## 1. Users

| Task | Permission |
|---|---|
| View users | `USER_VIEW` |
| Create, activate, deactivate, unlock, reset password or MFA, set roles | `USER_ADMINISTER` |
| Revoke sessions | `SESSION_REVOKE` |

**Create a user.**
1. **Users** → **New**: **Username** (lower case, 3–64 characters: letters, digits, `.`, `_`, `-`), **E-mail**,
   **Display name**, **User type** and a **Temporary password**; choose the roles; **Create user**.
2. Give the user the temporary password through a separate channel. At the first sign-in they enrol their
   authenticator app and choose their own password.
3. External auditors: access can end automatically on an expiry date. The user page shows **Access expires**, but
   neither user screen can set it yet; only the users API accepts `accessExpiresAt` today. Until the screen is
   added, deactivate external auditors when their engagement ends.

**User page actions** (**Account actions**):

| Action | Effect |
|---|---|
| **Issue password** | Sets a new temporary password; the user must change it at the next sign-in |
| **Reset MFA** | Removes the authenticator and withdraws the user's unused recovery codes; the user enrols again at the next sign-in. Needed only when the user has no recovery code left |
| **Revoke sessions** | Signs the user out everywhere immediately |
| **Unlock** | Lifts a lockout after repeated wrong passwords |
| **Deactivate** / **Activate** | Blocks or restores sign-in; history is kept (users are never deleted) |

**Roles of a user.**
* Choose roles and save. CAS refuses combinations that break a BLOCK segregation-of-duties rule and names the
  conflict. WARN rules are shown but allowed.
* If the change adds a role carrying financial permissions, it is not applied at once. It becomes a request under
  **Access grants**, and a second person with `ACCESS_GRANT_APPROVE` (chief accountant or finance manager) must
  approve it (C-19).
* Each user can be assigned to an office and department; the assignment is shown on the user page.

## 2. Roles and permissions (`ROLE_VIEW`, `ROLE_ADMINISTER`)

**Roles & permissions** lists the 14 seeded roles. They are proposals until MIAA signs off the role matrix
(C-08). Open a role, tick or untick permissions and **Save permissions**. CAS re-checks every holder of the role
against the SoD rules and refuses the change if a holder would end up with a conflict.

## 3. Access governance

* **Access grants** (`ACCESS_GRANT_APPROVE`): pending requests to add financial roles. Approve or reject; you
  cannot decide a request about yourself or one you made.
* **Access reviews** (`ACCESS_REVIEW_MANAGE` to run, `ACCESS_REVIEW_PERFORM` to review):
  1. **Start review cycle** with a **Cycle name** and **Due date**. Each active user gets a review.
  2. Reviewers record, with a note, **Retain**, **Modify** or **Revoke** for each user. A Modify or Revoke
     outcome is carried out through the user's roles (**Change roles**), then marked **Record as applied**.
  3. **Close cycle** succeeds only when every review, and every change it led to, is done.

  The review frequency is MIAA's choice.
* **Approval limits** (`APPROVAL_LIMIT_MANAGE`): the **Maximum amount (₱)** a role may approve on one document of a
  type; **Save limit**. No limits are seeded (C-24). With none set, approvals are unlimited by amount and still
  follow the workflow.

## 4. Organisation (`ORGANIZATION_ADMINISTER`)

**Organisation**:
* **Add office** (**Office code**, **Office name**);
* add departments under an office, with the cost centre their work is usually charged to;
* activate or deactivate offices and departments.

MIAA's structure is entered here; none is seeded.

## 5. Finance configuration

| Area | Page | Permission | Notes |
|---|---|---|---|
| Chart of accounts | **Chart of accounts** → **Import approved chart (CSV)**; edit accounts | `ACCOUNT_ADMINISTER` | Development uses a [PLACEHOLDER] chart (PLC- codes). Import MIAA's approved chart before go-live (C-22). Accounts can be deactivated, not deleted, once used |
| Cost centres | **Chart of accounts** / cost centres | `ACCOUNT_ADMINISTER` | |
| Control-account mappings | **Finance settings** → **Control-account mappings** | `ACCOUNT_ADMINISTER` | Accounts used by automatic postings, for example AP trade payable, input VAT, GR/IR, inventory revaluation. Every key must be mapped before the module can post |
| Tax codes | **Finance settings** → **Tax codes**: **Tax code**, **Tax description**, **Tax type**, **Rate**, **Tax account** | `ACCOUNT_ADMINISTER` | Seeded codes are [PLACEHOLDER] pending BIR confirmation (C-06) |
| Bank accounts | **Finance settings** → **Bank accounts**: **Bank account code**, **Bank name**, **Account name**, **Last four digits**, **Cash GL account** | `BANK_ACCOUNT_MANAGE` | Only the last four digits are stored |
| Open-item managed accounts | **Open items** → **Account to manage by open item** → **Manage by open item**; **Stop managing** | `ACCOUNT_ADMINISTER` | Postable asset or liability accounts only, such as GR/IR, suspense and advances |
| Fiscal years and periods | **Fiscal periods** → **Create fiscal year**; open periods | `PERIOD_OPEN` | Create the next year before it starts |
| Approval workflows | Seeded steps per document type | — | Voucher workflows (review, approve) and document workflows (for example DV certify then approve) are seeded; amount thresholds await C-24 |
| Budgets | **Budgets** | `BUDGET_PREPARE`, `BUDGET_APPROVE` | Enforcement is BLOCK in production (`CAS_BUDGET_ENFORCEMENT`) |
| Supplier bank verification | Supplier → **Bank details** | `SUPPLIER_BANK_VERIFY` | Second person only |
| Portal access | **Portal access** | `PORTAL_ACCESS_APPROVE` | Links a portal user to a customer after a second person checks the identity evidence (C-18) |

## 6. Close calendar (`CLOSE_TASK_MANAGE`)

On **Close calendar** → **Close tasks**, set each task's:
* **Task code** and **Task name**;
* **Checklist** (day, month, quarter or year end);
* **Due after (days)**: days after the day, period, quarter or year end;
* **Responsible permission**: the permission whose holders may tick it off, for example `PERIOD_CLOSE` or
  `DEPOSIT_PREPARE`;
* **Instructions** and **Order**.

Choose **Add task**, or **Edit** then **Save task**. **Deactivate** retires a task without losing its history.

Four [PLACEHOLDER] tasks are seeded until MIAA supplies its close calendar (C-33). Day-end tasks fall on weekdays;
public holidays are not considered for close tasks.

## 7. Tickets and service levels (`TICKET_ADMIN`)

On **Service levels**:
* **SLA policies**: per **Contract phase**, ticket type and priority, set **Respond within (min)**,
  **Resolve within (min)** and business hours or 24x7; **Set policy**. A new policy replaces the active one for
  the same combination. Seeded values are [PLACEHOLDER] (C-15).
* **Holidays**: **Date** and **Holiday name** → **Add holiday**. Business-hours SLA clocks skip them.
* **Ticket categories**: **Category code**, **Category name**, **Order** → **Add category**; **Deactivate** or
  **Reactivate**. Six [PLACEHOLDER] categories are seeded (C-34).
* **SLA compliance** summary for a date range.

Support agents (`TICKET_WORK`) acknowledge, assign, work and resolve tickets. Change requests are approved by
`TICKET_APPROVE` holders (finance manager), never the requester or assignee. Resolved tickets close
automatically after 7 days if the requester neither confirms nor reopens them.

## 8. Integrations (`INTEGRATION_VIEW`, `INTEGRATION_MANAGE`)

**Integrations** lists outbound transmissions per channel. The BIR EIS adapter is a **SIMULATED** sandbox until
BIR's specification is available (C-06).
* **Queue** sends posted billings and receipts to BIR EIS (sandbox).
* Filter by **Channel**, **Status** and dates. Every attempt and its response is kept.
* **Retry** a DEAD or REJECTED transmission after fixing the cause; **Cancel** one that must not be sent.

A transmission is never sent twice for the same document. Dead transmissions raise an alert to permission
holders.

## 9. Audit trail and security events

| Task | Permission |
|---|---|
| Audit trail | `AUDIT_LOG_VIEW` (auditors) |
| Security events | `SECURITY_EVENT_VIEW` (administrators, internal auditors) |

* **Audit trail** → **Event trail**: choose audit or security events and filter by action, actor, resource and
  date. Security events include sign-ins, failures, lockouts, MFA changes, session revocations, role changes and
  development bypass use, each with a severity.
* **Verify integrity** recomputes both hash chains and reports, per chain, whether it is intact, the rows checked
  and the first invalid sequence number, if any. Run it after
  restores and as part of release smoke checks.
* Exports: **My reports** → audit trail export (CSV, XLSX or PDF), by date range, action and actor.
* Alerts: HIGH-severity security events and dead integrations notify permission holders in the app and by
  e-mail, throttled per key.

## 10. Background work and operations

* Report jobs run in the background. Users see their own under **My reports**; files are kept encrypted and
  expire.
* Scheduled reports queue ordinary jobs for their owner.
* The ticket SLA monitor runs every minute: breaches, at-risk warnings, auto-close.
* E-mail goes through the outbox. Until SMTP is configured (C-16), production e-mail is off. In development,
  e-mails are written to the log.
* **Logs** mask passwords, tokens, one-time codes, TINs, account numbers and e-mail addresses. Never ask users
  for those values in tickets or e-mail.
* **Rate limits**: 600 API requests per minute per user and 120 per address when not signed in, shared by every
  server. Exceeding them gives a "too many requests" error with a retry time.
* **Encryption key**: the data encryption key protects MFA secrets, supplier bank accounts and stored files. It is
  held in the secrets manager by MISD; losing it makes those unreadable (docs/19 §3).
* **Restores**: after any database restore, run **Verify integrity** and the smoke checks in docs/19 §6.
