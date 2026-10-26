# UAT: opening balances and the parallel run

Files use the CAS templates (download them from each page); mapping the legacy extracts onto them is part of the
migration plan (C-12).

### UAT-MIG-001 The opening trial balance becomes an opening voucher
- RTM: FR-MIG-01..13, FR-GL-16
- Roles: Accountant (uploads), the journal approvers and poster

| Step | Action | Expected result |
|---|---|---|
| 1 | Opening balances: upload a trial balance that does not balance | Kept as INVALID with both totals; nothing can be created from it |
| 2 | Upload one with an unknown account and one control-account line without its subsidiary code | Each problem is listed against its line |
| 3 | Upload the correct file for the first day of the year | VALIDATED, with row count and total |
| 4 | Create the opening voucher | A draft OPENING voucher prepared by the uploader, with the file attached |
| 5 | Approve and post it through the journal workflow | Posted; the trial balance shows the opening balances |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-MIG-002 Open invoices must agree with the opening entry
- RTM: FR-MIG-01..13
- Roles: Accountant (uploads), Chief Accountant (applies)

| Step | Action | Expected result |
|---|---|---|
| 1 | Upload open customer invoices whose total for one customer differs from the opening entry | The difference is shown for that customer; the batch cannot be applied |
| 2 | Cancel it, then upload the corrected file | All parties agree |
| 3 | The uploader looks for Apply | Not offered |
| 4 | The Chief Accountant applies it | The invoices appear in aging, statements and the portal; nothing new is posted |
| 5 | Upload the same file again | Refused: already applied |
| 6 | Repeat steps 2 to 4 for unpaid supplier invoices | Same behaviour; the invoices can then be paid with a DV |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-MIG-003 Assets, stock, unapplied collections and supplier advances
- RTM: FR-MIG-01..13, FR-FA-01..12, FR-INV-01..10
- Roles: Accountant (uploads), Chief Accountant (applies)

| Step | Action | Expected result |
|---|---|---|
| 1 | Upload the fixed-asset register with cost, accumulated depreciation and remaining life | Agrees per asset and accumulated-depreciation account; once applied, each asset shows its legacy accumulated depreciation and a monthly charge over the remaining life |
| 2 | Upload stock on hand per item and warehouse at unit cost | Agrees per inventory account; once applied, stock and average cost are set; items that already had stock are refused |
| 3 | Upload unapplied customer collections | Once applied, they show as the customers' credits, can be applied to invoices, and never appear for deposit |
| 4 | Upload supplier advances | Once applied, they appear in Payables reports > Supplier advances, which agrees with the ledger |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______

### UAT-MIG-004 Parallel-run comparison and sign-off
- RTM: FR-MIG-01..13
- Roles: Accountant (uploads and explains), Chief Accountant (signs off)
- Preconditions: a month processed in both the legacy system and CAS; the legacy trial balance mapped to CAS codes

| Step | Action | Expected result |
|---|---|---|
| 1 | Parallel run: upload the legacy trial balance for the month, comparing closing balances | Every account is classed as agreeing, different, only in CAS, only in legacy, or unmapped |
| 2 | The Chief Accountant tries to sign off | Not possible while any difference is unexplained |
| 3 | Explain each difference (cut-off, mapping, correction pending…) | Explanations recorded with name and time |
| 4 | Post a correction in CAS and compare again | Explanations stay only where the difference is unchanged |
| 5 | The uploader looks for sign-off | Not offered |
| 6 | The Chief Accountant signs off with a note | SIGNED_OFF; no further changes; the comparison is evidence for "no unexplained discrepancies" (§18) |

- Result: ☐ Pass ☐ Fail ☐ Blocked · Tester: ______ · Date: ______ · Defect: ______
