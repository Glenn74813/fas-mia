# CAS Administrator Manual

For system administrators (users, roles, organisation, integrations, tickets) and finance administrators
(chief accountant and finance manager: chart of accounts, settings, close and approval configuration). Version for
schema V53, October 2026. Installation and releases are in [19-deployment-and-rollback.md](../19-deployment-and-rollback.md).

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
3. **Access expires**: the last day the user can sign in; access ends at 23:59 that day (Philippine time).
   * **Required for external auditors**, and it cannot be removed from them.
   * Optional for other users, for example temporary staff; leave it empty for no expiry.
   * A new or changed date must be in the future. To end access today, **Deactivate** the user instead.
   * To extend or shorten it later, open the user and change **Access expires** in the **Profile** section, which
     also edits the e-mail and display name. Every change is in the audit trail with the old and new values.
   * Once the date passes, sign-in is refused, and expired users drop out of access reviews and alerts.

**User page actions** (**Account actions**):

| Action | Effect |
|---|---|
| **Issue password** | Sets a new temporary password; the user must change it at the next sign-in |
| **Reset MFA** | Removes the authenticator, the user's passkeys and their unused recovery codes; the user enrols again at the next sign-in. Needed only when the user has no recovery code left |
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

### 8.1 API access (`INTEGRATION_VIEW` to view; `API_CLIENT_MANAGE`, the chief accountant, to change)

**Administration → API access** lets other systems read CAS data and hear about events (TOR §4.6).

**API clients.** **Create client** with a name and one or more read scopes (ledger, payables, receivables,
treasury, budgets, assets, inventory, reports). The client id and secret are shown **once**; give them to the
other system's administrator through a secure channel. The system then asks for a token:

```
POST /api/v1/auth/token
Content-Type: application/x-www-form-urlencoded
Authorization: Basic base64(client_id:client_secret)

grant_type=client_credentials
```

and calls the API with `Authorization: Bearer <access_token>` until the token expires (10 minutes). A client can
only read within its scopes, plus send interface files for review if it has `INTERFACE_SUBMIT` (§8.2); it cannot
create, approve or post anything. **New secret** replaces the secret (the
old one stops working at once); **Revoke** ends the client and its tokens at once. Every token issued and every
failed attempt is a security event; each client has its own rate limit.

**Webhooks.** **Subscribe** an HTTPS URL to events (journal voucher posted, invoice approved or posted, DV approved,
paid or posted, billing, receipt and deposit posted, budget approved, fiscal year closed). The signing secret is
shown once. Each event is sent as a small JSON message:

```
{"id": "<event id>", "type": "JOURNAL_VOUCHER_POSTED", "occurredAt": "...", "resource": {"type": "...", "id": "..."}}
```

with headers `X-CAS-Event`, `X-CAS-Delivery`, `X-CAS-Attempt` and `X-CAS-Signature: t=<unix time>,v1=<hex>`,
where `v1` is HMAC-SHA-256 of `<t>.<body>` with the secret. The receiver should check the signature, reject old
`t` values, answer 2xx quickly and fetch the document through the API. Failed deliveries are retried after 1 min,
5 min, 30 min, 2 h, 6 h and 12 h; then they are marked failed and can be sent again from **Deliveries**. Ten
failed deliveries in a row disable the subscription and alert its managers; **Resume** it once the receiver
works. **Send test** sends a `PING` event. URLs on this server or on private, link-local or internal networks are
refused (`cas.webhooks.allow-local-targets` exists for development only and is refused in production).

### 8.2 Inbound files (payroll, FMIS budget, assets)

**Administration → Inbound files** takes files from other systems (TOR §4.6.2). Choose the **Interface**, the
**File** (.csv, .json or .xml, up to 5 MB and 5,000 rows), a **Reference** (the sending system's batch number) and
the interface's header value, then **Check file**. The file becomes a batch:

* **VALIDATED**: every row passed; **Create drafts** makes the drafts, or **Reject** with a reason.
* **INVALID**: the problems are listed by line; correct the file and send it again, or **Reject** it.

| Interface | Permission to accept | Header | Row fields | Accepting creates |
|---|---|---|---|---|
| Payroll summary | `JOURNAL_CREATE` | `period` (YYYY-MM) or `accountingDate` | `account_code`, `cost_center_code`, `description`, `debit`, `credit` (must balance) | One draft journal voucher dated the period's last day |
| FMIS budget | `BUDGET_PREPARE` | `fiscalYear` | `account_code` (budget-controlled), `cost_center_code`, `amount` | The year's budget if there is none, and its draft allocations replaced by the file's |
| Asset information | `FA_MANAGE` | `offsetAccountCode` | `category_code`, `description`, `serial_number`, `location`, `custodian`, `cost_center_code`, `acquisition_date`, `in_service_date`, `cost`, `residual_value`, `useful_life_months` | Draft fixed assets (acquired as OTHER, credited to the offset account when activated) |

Field names ignore case, spaces, dashes and underscores. In JSON the header fields sit at the top level with the
rows in a `lines` array; in XML the header is the root's attributes or plain child elements and each row is an
element with attributes or child elements. Other systems send the same files with
`POST /api/v1/interfaces/{PAYROLL|FMIS_BUDGET|ASSETS}/batches` (Content-Type `text/csv`, `application/json` or
`application/xml`, header values as query parameters) using an API client with the `INTERFACE_SUBMIT` scope;
holders of the permission are notified, and only a person can accept the batch. The same file (by SHA-256) is
taken once. **These layouts are proposals until MIAA supplies each system's own (C-37).**

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
  e-mail, throttled per key. New high or medium anomaly findings notify `ANOMALY_REVIEW` holders the same way
  (at most every 30 minutes).
* Anomaly detection (**Audit → Anomalies**): `cas.anomalies.enabled` (default on), `cas.anomalies.cron` (default
  minute 20 of every hour, Manila time), `cas.anomalies.lookback-days` (90) and `cas.anomalies.round-amount-min`
  (100000). Holidays for the off-hours rule come from the business holidays used by ticket SLAs.

## 10. Data export (`DATA_EXPORT`: system administrators and finance managers)

**Administration → Data export** produces a full copy of the system's data for MIAA (TOR §3.6), for example a
year-end copy for the Records Office or the hand-back at the end of the contract.

1. Enter the **Purpose** and choose **Request full export**. Other holders of `DATA_EXPORT` are notified.
2. Another holder (never the requester) chooses **Approve**, or **Reject** with a reason.
3. The export is built in the background, usually within a minute, and the requester is notified.
4. The requester chooses **Download**. The file can be downloaded for 7 days (`cas.exports.retention`); then it is
   deleted.

The ZIP contains:

| Path | Content |
|---|---|
| `tables/<table>.csv` | Every table, one consistent snapshot, CSV (UTF-8, header row; empty field = NULL) |
| `tables/supplier_bank_account_numbers.csv` | Supplier bank account numbers, decrypted |
| `documents/<id>/<file name>` | Every supporting document, as uploaded |
| `dictionary.json` | Every table and column with its type and nullability |
| `manifest.json` | Who asked and approved, the purpose, the schema version, row counts and SHA-256 per file, what was left out and why, and the audit-chain verification |
| `SHA256SUMS` | The checksum of every file, for `sha256sum -c` |

Left out: password hashes, authenticator secrets, recovery, e-mail and reset codes, session tokens, passkey
challenges, the request replay cache, and generated report and bank files (they can be produced again). The file is
encrypted at rest under `cas.exports.dir` (set it to a protected volume in production) and decrypted as it is
downloaded. Every request, decision, generation, download and expiry is in the audit trail.

## 11. Records retention

**Administration → Records retention** (viewing: `RECORDS_RETENTION_MANAGE`, `DATA_EXPORT` or `AUDIT_LOG_VIEW`)
lists every fiscal year with:

* **Keep until**: the end of the year plus `cas.records.retention-years` (10, TOR §4.5);
* **Hold**: whether a legal hold covers it;
* **Archives**: the archives made of it, each with its SHA-256.

**Archiving a closed year** (`DATA_EXPORT`): choose **Archive** on a closed year and state the purpose. As with a
full export (§10), another holder approves it and the requester downloads it from **Data export**. The archive
holds the year's journals and entries, payables, disbursements and checks, billings, receipts and deposits, bank
statements and reconciliations, budgets, depreciation, asset and inventory documents, their lines, approval
decisions and supporting documents, the year's audit trail, and the reference data needed to read them (accounts,
cost centres, suppliers, customers, users without sign-in secrets). Supplier bank numbers keep only their last
four digits. When it is built, its SHA-256 is written to the audit trail as `FISCAL_YEAR_ARCHIVED`, so any copy
can later be checked against the chain. Keep archives on write-once storage **[MIAA infrastructure]**.

**Legal holds** (`RECORDS_RETENTION_MANAGE`, the chief accountant): place a hold on one fiscal year or on every
year with a reason and an optional reference (for example an audit observation memo), and release it with a note.
Both are audited.

**Disposal**: there is no purge. Posted records and the audit trail are kept until MIAA decides how lawful disposal
works (C-05, C-13).

## 12. Background work and operations

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
