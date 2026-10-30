import type { ModuleHelp } from './types';

export const ADMINISTRATION_HELP: Record<string, ModuleHelp> = {
  '/migration': {
    title: 'Opening balances',
    summary: 'Loads balances at cutover from CAS CSV templates. The opening trial balance becomes a draft opening journal voucher; open items, collections, advances, the asset register and stock are then tied to the posted opening entry.',
    steps: [
      'Choose a Batch type, then click Download the empty template and fill it in from the legacy extract.',
      'For the GL opening trial balance enter the Cutover date; for every other type enter the Opening entry number of the posted opening entry.',
      'Add a Batch description, choose the CSV file and click Upload and validate.',
      'Open the batch and fix any rows listed under Rows with problems; upload a corrected file as a new batch if needed.',
      'GL batch: click Create opening voucher, then submit, approve and post the draft voucher through Journal vouchers.',
      'Other batches: check the Agreement with the opening entry panel, then a second person clicks Apply batch. Use Cancel batch with a reason to abandon one.',
    ],
    roles: 'Accountant uploads and validates batches and, for the GL opening, creates the voucher and becomes its preparer. Chief Accountant applies the open-item, asset and stock batches; the uploader cannot apply their own. The opening voucher follows the usual four-eyes journal approval.',
    connections: [
      { to: '/journal-vouchers', how: 'The GL opening batch creates a draft opening journal voucher with the uploaded file attached; it is approved and posted like any other voucher.' },
      { to: '/chart-of-accounts', how: 'Opening trial balance lines are mapped to accounts in the chart of accounts.' },
      { to: '/receivables/billings', how: 'Open customer invoices and unapplied collections are created as posted records against the opening entry, without posting again.' },
      { to: '/payables/invoices', how: 'Unpaid supplier invoices and supplier advances are created as posted records against the opening entry, without posting again.' },
      { to: '/fixed-assets', how: 'The fixed-asset register batch loads the assets that exist at cutover.' },
      { to: '/migration/parallel-runs', how: 'After cutover, the parallel run compares each month of the legacy trial balance with CAS.' },
    ],
  },

  '/migration/parallel-runs': {
    title: 'Parallel run',
    summary: 'Compares each legacy trial balance with CAS account by account during the parallel-run period. Every difference needs a written explanation before a second person signs the comparison off.',
    steps: [
      'Choose the CAS period, whether to compare Closing balances or The period’s movements, and name the Legacy system.',
      'Choose the legacy trial balance CSV (use Download the empty template for the layout) and click Compare.',
      'Open the comparison and review the Accounts table; tick Show accounts that agree to see every line.',
      'For each difference, type the reason in the Explanation box (at least 10 characters) and click Save.',
      'If CAS or the file changes, click Compare again with CAS.',
      'When no line is unexplained, a second person adds a Sign-off note and clicks Sign off. Cancel comparison abandons a run.',
    ],
    roles: 'Accountant uploads and compares, and can explain differences. Chief Accountant signs off, but not a run they uploaded themselves, and only when nothing is left unexplained.',
    connections: [
      { to: '/general-ledger/trial-balance', how: 'CAS amounts in the comparison come from the ledger for the chosen period.' },
      { to: '/chart-of-accounts', how: 'Legacy account codes are matched to CAS accounts; unmatched ones are shown as Legacy account not mapped.' },
      { to: '/fiscal-periods', how: 'Only periods that are not future can be compared.' },
      { to: '/migration', how: 'The opening balances loaded at cutover are the starting point the monthly comparisons build on.' },
    ],
  },

  '/tickets': {
    title: 'Support tickets',
    summary: 'Raise and follow requests to application support: incidents, service requests, change requests and enhancements. Each ticket carries a service-level clock and a full history.',
    steps: [
      'Click New ticket, choose the type, priority, severity and category, and add the optional Module.',
      'Enter a Title and description, without passwords or account numbers, then click Submit ticket.',
      'Use the filters for type, status, severity and category to find tickets, then open one to see its history and Service level.',
      'Support clicks Acknowledge, Assign and Start work, and can Put on hold with a reason; Resume continues the clock.',
      'Change requests and enhancements go through Submit for approval, Approve or Reject, Validate on staging and release notes before being resolved.',
      'After Resolve, the requester clicks Confirm and close, or Reopen with a reason; otherwise it closes automatically as unconfirmed.',
    ],
    roles: 'Every internal role can raise and follow its own tickets. Application Support and System Administrator work tickets. Finance Manager approves or rejects change requests and enhancements. Four-eyes: the requester cannot approve their own change, and the assignee cannot validate their own change on staging.',
    connections: [
      { to: '/tickets/sla', how: 'Response and resolution deadlines come from the service-level policies and holiday calendar for the ticket type and priority.' },
      { to: '/inbox', how: 'Change requests awaiting a decision appear in the approver’s Inbox.' },
      { to: '/audit', how: 'Ticket actions are recorded in the audit trail.' },
    ],
  },

  '/tickets/sla': {
    title: 'Service levels',
    summary: 'Sets how fast support must respond to and resolve tickets, which days do not count, and which categories tickets can use. It also reports SLA compliance.',
    steps: [
      'Check the current contract phase and business hours at the top of the page.',
      'In Active SLA policies, choose the phase, ticket type and priority, enter Respond within and Resolve within in minutes, and click Set policy.',
      'In Holidays, enter a Date and Holiday name and click Add holiday; these days are not counted on business-hours clocks.',
      'Under SLA compliance, set From and To to see how tickets performed in that range.',
      'In Ticket categories, enter a Category code, Category name and Order, then click Add category.',
    ],
    roles: 'System Administrator maintains policies, holidays and categories. Application Support, Finance Manager and Internal Auditor can view tickets and the compliance summary. The policy values are configurable, not fixed.',
    connections: [
      { to: '/tickets', how: 'New tickets pick up the active policy for their phase, type and priority; setting a policy replaces the active one.' },
      { to: '/admin/roles', how: 'Which roles can view or work tickets is controlled by their permissions.' },
    ],
  },

  '/admin/users': {
    title: 'Users',
    summary: 'Create and maintain user accounts: profile, roles, access expiry, password and MFA resets, and activation. Everyone with the right to view users can search the list.',
    steps: [
      'Search by name or username and use the Status filter to find a user.',
      'Click New user, choose Internal, External auditor or Concessionaire, fill in Username, E-mail and Display name, set Access expires if needed, and issue a Temporary password.',
      'Open a user to Save profile, or to use Deactivate or Activate, Unlock, Revoke sessions, Reset MFA and Issue password.',
      'Under Roles, tick the roles and click Save roles. You cannot change your own roles.',
      'Review the Segregation-of-duties warnings and the effective permissions shown on the page.',
      'If a role carries financial permissions, the change waits as a pending grant for a second person to approve.',
    ],
    roles: 'System Administrator creates and maintains users. Revoking sessions needs the session-revoke permission. Technical administrators cannot hold financial permissions, and conflicting role combinations are blocked by segregation-of-duties rules.',
    connections: [
      { to: '/admin/access-grants', how: 'Adding a role with financial authority creates one pending role grant request per user, approved by a different person.' },
      { to: '/admin/roles', how: 'The roles you can assign, and the permissions they carry, are defined on the Roles page.' },
      { to: '/admin/organization', how: 'An internal user’s office and department assignment is set on the user page and maintained under Organisation.' },
      { to: '/admin/access-reviews', how: 'Reviewers send role changes from an access review here for user administrators to apply.' },
      { to: '/profile', how: 'Users change their own password, MFA and passkeys on their profile; Reset MFA here makes them enrol again.' },
    ],
  },

  '/admin/roles': {
    title: 'Roles & permissions',
    summary: 'Shows each role, the permissions it carries and how many active users hold it. Roles are the unit of access in CAS; the seeded matrix is a proposal pending MIAA sign-off.',
    steps: [
      'Read the table of Code, Name, Permissions and Active users.',
      'Click a role code to see its permissions grouped on the role page.',
      'To change a role, tick or untick permissions and click Save permissions.',
      'Read any segregation-of-duties warning before saving; blocked combinations cannot be saved.',
    ],
    roles: 'System Administrator edits roles, but cannot edit a role they hold themselves. Auditors and approvers can view roles read-only.',
    connections: [
      { to: '/admin/users', how: 'Users receive permissions only through the roles assigned on their user page.' },
      { to: '/admin/access-grants', how: 'Giving a role financial permissions makes later grants of that role wait for approval.' },
      { to: '/admin/approval-limits', how: 'Approval limits are set per role and document type.' },
      { to: '/admin/access-reviews', how: 'Access reviews check the roles users hold against what they need.' },
    ],
  },

  '/admin/organization': {
    title: 'Organisation',
    summary: 'Maintains the organisation’s offices and departments, and the cost centre a department’s work is usually charged to. It also records which office each user belongs to.',
    steps: [
      'Under an organisation, enter an Office code and Office name and click Add office.',
      'For an office, enter a department Code and Department name, optionally choose a Cost centre, and add it.',
      'Use Deactivate or Reactivate on an office or department when it opens or closes.',
      'To assign a user, open the user and use Office assignment: choose Office, Department and From date, then save.',
      'To change an assignment, End assignment or set End on, then assign the new office.',
    ],
    roles: 'System Administrator maintains organisations, offices, departments and office assignments.',
    connections: [
      { to: '/admin/users', how: 'Office assignment is edited on the user page and shows the user’s current office and department.' },
      { to: '/finance-settings', how: 'Cost centres offered for departments are the active cost centres set up in Finance settings.' },
      { to: '/budgets', how: 'Departments and their cost centres tie organisational units to the budgets charged against them.' },
    ],
  },

  '/admin/access-grants': {
    title: 'Financial access grants',
    summary: 'Holds role changes that add financial permissions until a second person approves them. This is the check that stops an administrator from giving anyone financial authority alone.',
    steps: [
      'Open the Waiting tab to see pending requests; the other tabs show Approved, Rejected, Withdrawn or All.',
      'Read what each request adds: the financial permissions, the roles now and the roles requested.',
      'Type the basis for your decision in the note box, which is required to reject.',
      'Click Approve to apply the roles, or Reject to refuse them.',
      'The administrator who asked can click Withdraw while a request is waiting.',
    ],
    roles: 'Finance Manager and Chief Accountant hold the permission to approve. Neither the administrator who requested the change nor the user concerned can decide it. Only one pending grant per user is allowed.',
    connections: [
      { to: '/admin/users', how: 'Saving roles that add financial permissions on the user page creates the pending request.' },
      { to: '/inbox', how: 'Approvers get an Inbox item, and a bell notification, when a grant waits for approval.' },
      { to: '/admin/roles', how: 'Which permissions count as financial is defined in the role permission matrix.' },
      { to: '/audit', how: 'Requests and decisions are recorded in the audit trail.' },
    ],
  },

  '/admin/access-reviews': {
    title: 'Access reviews',
    summary: 'Periodic review of who has what access. A cycle lists every active internal user with their roles so reviewers can confirm them or ask for changes. How often cycles run is for MIAA to decide.',
    steps: [
      'To start a cycle, enter a Cycle name and Due date and click Start review cycle. Only one cycle can be open at a time.',
      'Open the cycle and use Waiting for me to see the users you review; Everyone shows all of them.',
      'For each user click Retain, Modify or Revoke. For Modify or Revoke, say in the note what should change.',
      'A user administrator opens Change roles, edits the user’s roles, then clicks Record as applied.',
      'When every user is reviewed and every change is applied, click Close cycle.',
    ],
    roles: 'Internal Auditor starts and closes cycles. Internal Auditor, Finance Manager and Chief Accountant perform reviews, never of their own access. System Administrator applies the changes but cannot also review.',
    connections: [
      { to: '/admin/users', how: 'Change roles opens the user page, where the reviewer’s requested change is applied.' },
      { to: '/admin/access-grants', how: 'A change that adds financial permissions still needs approval there.' },
      { to: '/inbox', how: 'Reviews and changes waiting for you appear in your Inbox.' },
      { to: '/audit', how: 'Review outcomes are recorded in the audit trail.' },
    ],
  },

  '/admin/approval-limits': {
    title: 'Approval limits',
    summary: 'The most a holder of a role may approve on one document of a given type. A user’s limit is the highest among their roles that have one; users with no limited role are not limited.',
    steps: [
      'Choose a Role and a Document type.',
      'Enter the Maximum amount (₱) and click Save limit. Saving again for the same pair replaces the amount.',
      'Review the table of limits already set.',
      'Click Remove, and confirm, to lift a limit.',
    ],
    roles: 'Finance Manager sets and removes limits. Auditors and role viewers can read them. The amounts are MIAA’s decision: none are set by default, and every change is recorded in the audit trail.',
    connections: [
      { to: '/admin/roles', how: 'Limits attach to roles, so they apply to every user holding that role.' },
      { to: '/journal-vouchers', how: 'A limit on a document type caps what an approver can approve on that type of document.' },
      { to: '/payables/disbursements', how: 'Payment approvers are held to the limit for their role.' },
      { to: '/audit', how: 'Limit changes appear in the audit trail.' },
    ],
  },

  '/admin/data-exports': {
    title: 'Data export',
    summary: 'A full copy of MIAA’s data in open formats: every table as CSV with a data dictionary, supporting documents, the audit-chain check and a SHA-256 manifest, in one ZIP. Sign-in secrets are left out.',
    steps: [
      'Enter a Purpose of at least 10 characters and click Request full export.',
      'A different holder of the export permission opens the request and clicks Approve, or Reject with a reason.',
      'Once the export shows Done, the person who requested it clicks Download.',
      'Check the SHA-256 shown against the ZIP, which also carries its own manifest of checksums.',
    ],
    roles: 'System Administrator and Finance Manager can request and approve. Two people are needed: the requester cannot approve their own request, and only the requester can download, for 7 days. Every step is recorded in the audit trail.',
    connections: [
      { to: '/admin/records-retention', how: 'Year archives for retention are requested on the Records retention page and approved the same way.' },
      { to: '/audit', how: 'The export includes the audit-chain check, and each request, decision and download is logged.' },
      { to: '/inbox', how: 'Requests waiting for a second person’s approval appear in the Inbox.' },
    ],
  },

  '/admin/records-retention': {
    title: 'Records retention',
    summary: 'Shows how long each fiscal year’s records must be kept, lets you archive a closed year, and records legal holds that stop records being disposed of.',
    steps: [
      'Read the retention schedule and the disposal notice at the top of the page.',
      'For a closed fiscal year, click Archive and confirm; a second holder of the export permission must approve it.',
      'Download the approved archive and note its SHA-256.',
      'To place a legal hold, pick the fiscal year (or Every fiscal year), add an optional Reference and a Reason, and click Place hold.',
      'When the hold ends, click Release and give a reason.',
    ],
    roles: 'Chief Accountant places and releases legal holds. Holders of the data export permission (System Administrator, Finance Manager) request and approve archives, under the two-person rule. The retention period shown is read from configuration.',
    connections: [
      { to: '/admin/data-exports', how: 'Archives use the same two-person request and approval flow as full data exports.' },
      { to: '/fiscal-periods', how: 'Only fiscal years that are closed can be archived.' },
      { to: '/close', how: 'Closing a year is what makes its records eligible for archive.' },
      { to: '/audit', how: 'The archive includes the year’s audit trail; holds and releases are logged.' },
    ],
  },

  '/integrations': {
    title: 'Integrations',
    summary: 'Shows each outbound channel, whether it is enabled or simulated, and the log of every transmission. You can queue documents and retry or cancel transmissions that failed.',
    steps: [
      'Read the channel cards for the mode, availability and counts of transmissions.',
      'To send posted billings and receipts to BIR EIS, set From and To and click Queue.',
      'Use the Channel and Status filters to find transmissions.',
      'Open a transmission to see its idempotency key and payload checksum.',
      'Retry or cancel a transmission that is stuck or failed.',
    ],
    roles: 'Chief Accountant and System Administrator can queue, retry and cancel. Accountant, Finance Manager and the auditors can view the log. Documents are sent in CAS’s own format, not yet the BIR EIS schema; production sending waits for BIR’s specification and permit.',
    connections: [
      { to: '/receivables/billings', how: 'Posted billings are queued for BIR EIS from here.' },
      { to: '/receivables/receipts', how: 'Posted receipts are queued for BIR EIS from here.' },
      { to: '/treasury/payment-files', how: 'Bank payment files and their acknowledgements are tracked as transmissions.' },
      { to: '/integrations/api-access', how: 'Systems that read CAS data, and webhook deliveries, are managed under API access.' },
    ],
  },

  '/integrations/inbound': {
    title: 'Inbound files',
    summary: 'Receives payroll summaries, FMIS budget data and asset information from other systems as CSV, JSON or XML. Each file is checked on arrival, and accepting it creates drafts, never posted records.',
    steps: [
      'Choose the Interface, then the file (.csv, .json or .xml).',
      'Enter the Reference (the sending system’s batch number) and the header field shown, such as the payroll period or fiscal year.',
      'Click Check file and open the batch to read any Problems found.',
      'If it is valid, click Create drafts. Otherwise click Reject and give a reason.',
      'Follow the draft links to review and approve them in the target module.',
    ],
    roles: 'You can only use the interfaces you hold the permission for: journal creation for payroll, budget preparation for FMIS budget, and fixed-asset management for asset information. Drafts then follow the normal four-eyes approval.',
    connections: [
      { to: '/journal-vouchers', how: 'A payroll summary creates a draft journal voucher.' },
      { to: '/budgets', how: 'FMIS budget data creates draft budget allocations.' },
      { to: '/fixed-assets', how: 'Asset information creates draft fixed assets.' },
      { to: '/integrations/api-access', how: 'Other systems can also send these files through the API.' },
    ],
  },

  '/integrations/api-access': {
    title: 'API access',
    summary: 'Lets other systems read CAS data through the API as API clients with read scopes, and subscribe to webhooks that announce events such as a voucher being posted.',
    steps: [
      'Enter a Client name, tick the read scopes it needs and click Create client.',
      'Copy the client secret straight away; it is shown only once. Click I have saved it.',
      'The other system gets a short-lived token from the token endpoint using its client ID and secret.',
      'Use New secret to rotate a secret, or Revoke to cut a client off.',
      'For webhooks, enter a Name and an HTTPS URL, tick the events and click Subscribe; save the signing secret.',
      'Use Send test, Pause, Resume or Deliveries to check and control a subscription.',
    ],
    roles: 'Chief Accountant manages API clients and webhooks. Messages are signed with the subscription’s secret, and the receiving system reads the details through the API.',
    connections: [
      { to: '/integrations', how: 'Outbound transmissions are monitored on the Integrations page.' },
      { to: '/integrations/inbound', how: 'Systems can send inbound files through the API as well as by upload.' },
      { to: '/journal-vouchers', how: 'A voucher being posted is one event a webhook can announce.' },
      { to: '/audit', how: 'Client and webhook changes are recorded in the audit trail.' },
    ],
  },

  '/audit': {
    title: 'Audit trail',
    summary: 'A tamper-evident record of business and administrative actions, and a separate record of security events such as sign-ins. Each record is chained to the last so changes can be detected.',
    steps: [
      'Pick the Business & admin or Security tab, whichever you are allowed to see.',
      'Type an action, such as USER_CREATED, in Filter by action to narrow the list.',
      'Read who did what and when, with the details of each event.',
      'Click Verify integrity to check that neither chain has been altered.',
    ],
    roles: 'Internal Auditor and External Auditor (COA) can view the audit trail and verify integrity. Auditors may not hold transaction roles, so they cannot originate or approve what they review.',
    connections: [
      { to: '/audit/anomalies', how: 'Unusual transactions are flagged separately as anomaly findings for review.' },
      { to: '/admin/users', how: 'User creation, role changes and resets are recorded here.' },
      { to: '/journal-vouchers', how: 'Posting and approval events for vouchers appear in the trail.' },
      { to: '/admin/data-exports', how: 'Data exports include the audit-chain check.' },
    ],
  },

  '/audit/anomalies': {
    title: 'Anomalies',
    summary: 'Transactions the system flagged as unusual, such as possible duplicate invoices, payments soon after a bank change, or amounts just below a threshold. A finding is not proof of wrongdoing.',
    steps: [
      'Read the counts of open High, Medium and Low findings.',
      'Filter by Status, Severity and Rule to focus the list.',
      'Open the document a finding points at to check the facts.',
      'Click Explain and record why it is not a problem, or Escalate and record what needs following up.',
      'Click Run detection now to check straight away; it also runs every hour.',
    ],
    roles: 'Internal Auditor, Chief Accountant and Finance Manager can see findings. Internal Auditor and Chief Accountant can explain, escalate and run detection. A finding is reviewed by someone not involved in the document.',
    connections: [
      { to: '/payables/invoices', how: 'Duplicate-invoice and near-threshold findings point to supplier invoices.' },
      { to: '/payables/suppliers', how: 'Payments soon after a bank-detail change are flagged against the supplier.' },
      { to: '/journal-vouchers', how: 'Backdated, off-hours and round-amount findings can point to vouchers.' },
      { to: '/audit', how: 'Reviews are recorded in the audit trail.' },
    ],
  },

  '/profile': {
    title: 'My profile',
    summary: 'Your own account: details, roles and permissions, password, two-step verification with recovery codes, and passkeys.',
    steps: [
      'Check your account details, last sign-in and when your session ends.',
      'Click Change password to set a new one.',
      'Under Two-step verification, see how many recovery codes are left. Enter your Current 6-digit code and click Generate new codes; save the new codes, as they are shown once.',
      'Under Passkeys, enter a Name for this passkey and your Current 6-digit code and click Add a passkey to sign in with fingerprint, face or device PIN.',
      'Review Your access to see your roles and the pages you can open.',
    ],
    roles: 'Every user manages their own profile. Your password is still needed with a passkey. If you lose your authenticator, a recovery code lets you sign in once; a System Administrator can reset your MFA.',
    connections: [
      { to: '/admin/users', how: 'A System Administrator resets MFA, issues temporary passwords and changes your roles there.' },
      { to: '/admin/roles', how: 'The roles and permissions shown under Your access are defined on the Roles page.' },
      { to: '/audit', how: 'Sign-in and security events are recorded in the security trail.' },
    ],
  },
};
