# UAT: access, security and audit trail

### UAT-AUTH-001 First sign-in: enrol an authenticator and replace the temporary password
- RTM: FR-AUTH-01, FR-AUTH-07, FR-AUTH-14, FR-UI-01
- Roles: System Administrator (creates the user); the new user
- Preconditions: an authenticator app on the tester's phone

| Step | Action | Expected result |
|---|---|---|
| 1 | Administrator: Users > New user; give a username, e-mail, a role and a temporary password; save | The user is created with status ACTIVE; the temporary password is not shown again |
| 2 | New user: sign in with the username and the temporary password | An enrolment screen shows a QR code and the secret; no other page is reachable |
| 3 | Scan the code, enter the six-digit code shown by the app | Enrolment completes; the system demands a new password before anything else |
| 4 | Set a password that meets the policy | The dashboard opens and shows the user's roles and "MFA Enrolled" |
| 5 | Sign out and sign in again with the e-mail address instead of the username | Sign-in asks for the authenticator code and succeeds |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AUTH-002 Every interactive sign-in needs the authenticator code
- RTM: FR-AUTH-01, FR-AUTH-07
- Roles: any enrolled user

| Step | Action | Expected result |
|---|---|---|
| 1 | Sign in with the correct password | The code screen appears; no data is visible yet |
| 2 | Enter a wrong code | "The verification code is not valid."; still not signed in |
| 3 | Enter the current code | Signed in |
| 4 | Sign out; sign in again within the same 30 seconds using the same code | The code is refused (a code works once) |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AUTH-003 The password policy refuses weak passwords
- RTM: FR-AUTH-02
- Roles: any user (Change password)

| Step | Action | Expected result |
|---|---|---|
| 1 | Try a password shorter than 12 characters | Refused, with the reason |
| 2 | Try 12+ characters using only lower-case letters and digits | Refused: needs three of upper-case, lower-case, digit, symbol |
| 3 | Try a password containing your username | Refused |
| 4 | Try a compliant password | Accepted; the next sign-in uses it |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AUTH-004 Repeated failures lock the account and are recorded
- RTM: FR-AUTH-10, FR-AUTH-13
- Roles: a test user; Internal Auditor (to view the security log)

| Step | Action | Expected result |
|---|---|---|
| 1 | Enter a wrong password five times | Each attempt gives the same generic message (it never says which part was wrong) |
| 2 | Enter the correct password | Still refused: the account is locked for 15 minutes |
| 3 | Auditor: Audit trail > security events for that user | Each failure and the lock are listed with time and client address |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AUTH-005 Forgotten password
- RTM: FR-AUTH-11
- Roles: any user with a working e-mail address in UAT

| Step | Action | Expected result |
|---|---|---|
| 1 | Sign-in page > Forgot password; enter the e-mail address | The same confirmation appears whether or not the address exists |
| 2 | Open the reset link from the e-mail and set a new compliant password | The password is changed; existing sessions end |
| 3 | Use the same link again | Refused: the link works once and expires after 30 minutes |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AUTH-006 Signing out, and an administrator ending a user's sessions
- RTM: FR-AUTH-06
- Roles: System Administrator; a test user signed in on two browsers

| Step | Action | Expected result |
|---|---|---|
| 1 | User: Sign out in browser A | Browser A returns to the sign-in page; Back does not show data |
| 2 | Administrator: Users > the user > end all sessions | Confirmed and audited |
| 3 | User: act in browser B | Browser B is signed out on its next request |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AUTH-007 Deactivating and reactivating an account
- RTM: FR-AUTH-12
- Roles: System Administrator; a test user

| Step | Action | Expected result |
|---|---|---|
| 1 | Administrator: deactivate the user | Status INACTIVE; the user's sessions end |
| 2 | User: try to sign in | Refused with the generic message |
| 3 | Administrator: reactivate; user signs in | Sign-in works again |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-ACC-001 Users see and reach only what their roles allow
- RTM: FR-RBAC-01, FR-RBAC-02, FR-UI-01
- Roles: a Cashier, an Accountant, an External Auditor

| Step | Action | Expected result |
|---|---|---|
| 1 | Sign in as each user in turn and compare the navigation | Each sees only the modules their permissions allow |
| 2 | As the Cashier, type the address of a page they cannot see (for example /admin/users) | Access denied; no data is shown |
| 3 | Administrator: Roles & permissions; change a role's permissions; the affected user signs in again | The navigation reflects the change |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-ACC-002 Conflicting duties cannot be combined
- RTM: FR-RBAC-03
- Roles: System Administrator

| Step | Action | Expected result |
|---|---|---|
| 1 | Give one user both a role that releases payments (Cashier) and one that manages supplier records | Refused: the segregation-of-duties rule and its reason are shown |
| 2 | Add a payment-release permission to a role that already maintains suppliers | Refused for the same reason |
| 3 | Assign a combination marked as a warning (for example invoice preparation and approval) | Allowed with a warning; the system still refuses one person approving their own invoice (UAT-AP-002) |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-ACC-003 Administrators hold no financial authority
- RTM: FR-RBAC-04
- Roles: System Administrator

| Step | Action | Expected result |
|---|---|---|
| 1 | Review the System Administrator role's permissions | No posting, approval, payment or budget permission |
| 2 | Try to add one (for example JOURNAL_POST) to the administrator's role, or give an administrator the Chief Accountant role | Refused by the segregation-of-duties rules |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-ACC-004 Nobody changes their own roles
- RTM: FR-RBAC-05
- Roles: System Administrator

| Step | Action | Expected result |
|---|---|---|
| 1 | Open your own user record and try to add or remove a role | Refused: self-modification is not allowed; the attempt is audited |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AUD-001 The audit trail is complete, searchable and tamper-evident
- RTM: FR-AUD-01, FR-AUD-02, FR-AUD-03, FR-AUTH-13
- Roles: Internal Auditor
- Preconditions: other scripts have run, so there is activity to find

| Step | Action | Expected result |
|---|---|---|
| 1 | Audit trail: filter by date range, action (for example JOURNAL_VOUCHER_POSTED) and actor | Matching events with time, actor, resource and details |
| 2 | Switch to security events and filter sign-in failures | Failures from UAT-AUTH-004 are present |
| 3 | Run the integrity verification | Both chains are reported intact, with the number of events checked |
| 4 | Confirm there is no way to edit or delete an event in the interface | None exists |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-AUD-002 Exporting the audit trail
- RTM: FR-AUD-03, FR-RPT-01..19
- Roles: Internal Auditor

| Step | Action | Expected result |
|---|---|---|
| 1 | My reports: request an audit-trail export for a date range as XLSX | The job runs in the background and becomes ready |
| 2 | Download it | Each row carries its hash; the file states whether the whole chain verified at export time |
| 3 | Sign in as a user without audit permission and try to download the same report | Refused |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______
