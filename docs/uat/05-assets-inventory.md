# UAT: fixed assets and inventory

Useful lives, residual values and the depreciation start convention are placeholders pending C-30.

### UAT-FA-001 Registering and activating assets
- RTM: FR-FA-01..12
- Roles: Accountant (registers), Chief Accountant (activates)
- Preconditions: a posted supplier invoice charged to an asset account; an open AUC project with costs

| Step | Action | Expected result |
|---|---|---|
| 1 | Asset register: register an asset from the invoice | Draft; cost cannot exceed what the invoice charged |
| 2 | Register an asset from the AUC project | Draft; the project's costs move to the asset on activation |
| 3 | The preparer tries to activate; a Chief Accountant activates | Preparer refused; ACTIVE with an asset number and its acquisition entry |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-FA-002 Monthly depreciation
- RTM: FR-FA-01..12
- Roles: Accountant (prepares the run), Chief Accountant (posts)

| Step | Action | Expected result |
|---|---|---|
| 1 | Depreciation: prepare the run for the month | A draft listing each asset's charge |
| 2 | Prepare a second run for the same month | Refused |
| 3 | A second person posts it | Expense debited per category and cost centre, accumulated depreciation credited |
| 4 | Skip a month, then run the next | The missed month is caught up; no month is charged twice |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-FA-003 Disposal, PPE schedule and reconciliation
- RTM: FR-FA-01..12
- Roles: Accountant, Chief Accountant

| Step | Action | Expected result |
|---|---|---|
| 1 | Dispose of an asset by sale with proceeds; post (second person) | Cost and accumulated depreciation removed; gain or loss recorded |
| 2 | Try to dispose of it again | Refused |
| 3 | PPE reports: schedule for the year | Opening, additions, disposals, depreciation and closing per category |
| 4 | Fixed-asset reconciliation | Register cost and accumulated depreciation equal their ledger accounts |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-INV-001 Stock movements at weighted average cost
- RTM: FR-INV-01..10
- Roles: Inventory Clerk (prepares), Chief Accountant (posts)

| Step | Action | Expected result |
|---|---|---|
| 1 | Stock documents: receive 10 units at 100 and 10 at 130 (two receipts, posted by a second person) | 20 on hand, value 2,300, average 115 |
| 2 | Issue 5 to a cost centre | Valued at 115 each; expense debited, inventory credited |
| 3 | Try to issue more than is on hand | Refused |
| 4 | Transfer between warehouses; count with a difference | Transfer moves quantity only; the count posts a gain or loss to the variance account |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-INV-002 GR/IR matching and inventory reconciliation
- RTM: FR-INV-01..10, FR-AP-01..15
- Roles: Accountant, Chief Accountant

| Step | Action | Expected result |
|---|---|---|
| 1 | Post the supplier invoice for a goods receipt, charged to the GR/IR account | Inventory reports: the receipt and invoice match; unmatched items are listed |
| 2 | Inventory reconciliation | Stock value equals the inventory accounts; GR/IR open items equal the clearing account |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______
