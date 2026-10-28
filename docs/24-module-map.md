# 24 — Module map: what each module owns and what it connects to

Generated from the code on 28 October 2026 (backend `com.miaa.cas.*` packages): for each module, the tables it
writes (and so owns), the other modules it calls, and the other modules whose data it reads. Re-run the inventory
when modules change; the method is at the end.

## 1. Business modules

| Module | Screens | Owns (tables) | Calls | Reads data of |
|---|---|---|---|---|
| **generalledger** | Chart of accounts, Trial balance, Account ledger, Open items, Fiscal periods | chart_of_accounts, cost_centers, subsidiary_ledger_accounts, gl_account_mappings, fiscal_years, accounting_periods, period_status_changes, ledger_balances, gl_clearings, gl_clearing_lines | — | journal (posted lines) |
| **journal** | Journal vouchers, Recurring journals, Overhead allocation | journal_vouchers, journal_voucher_lines, journal_entries, journal_entry_lines, approval_decisions, numbering_sequences, recurring_journals, recurring_journal_lines, allocation_cycles, allocation_rules, allocation_receivers, allocation_runs | generalledger, approvals, documents, identity | generalledger |
| **tax** | Finance settings → Tax codes | tax_codes | generalledger | generalledger |
| **accountspayable** | Suppliers, Supplier invoices, Disbursement vouchers, Payables reports | suppliers, supplier_bank_accounts, supplier_invoices, supplier_invoice_lines, supplier_advances, disbursement_vouchers, disbursement_voucher_lines, check_register | generalledger, journal, tax, budgeting, approvals, treasury, documents | generalledger, journal, tax, treasury |
| **accountsreceivable** | Customers, Billing, Collections, Inquiries, Portal access, concessionaire portal | customers, customer_contracts, billing_documents, billing_document_lines, receipts, receipt_allocations, portal_link_requests, portal_inquiries | generalledger, journal, tax, approvals, documents, notifications | generalledger, journal, tax, identity (portal users) |
| **treasury** | Bank accounts, Deposits, Daily collections, Bank statements, Reconciliation, Payment files, Cash position, Cash-flow forecast | bank_accounts, deposits, deposit_items, bank_statements, bank_statement_lines, bank_reconciliations, bank_matches, bank_match_ledger_lines, payment_batches, payment_batch_items | generalledger, journal, accountspayable, approvals, documents | accountspayable, accountsreceivable, budgeting, generalledger, journal |
| **budgeting** | Budgets, realignments, budget reports | budgets, budget_allocations, budget_adjustments, budget_reservations | generalledger, approvals | generalledger, journal |
| **fixedassets** | Asset register, Asset setup, Depreciation, Transfers, Disposals | asset_categories, fixed_assets, auc_projects, depreciation_runs, depreciation_lines, asset_transfers, asset_disposals | generalledger, journal | accountspayable (invoices capitalised), generalledger, journal |
| **inventoryaccounting** | Stock items, Inventory documents, GR/IR, Cost vs market review, Inventory reports | warehouses, inventory_items, inventory_balances, inventory_movements, inventory_documents, inventory_document_lines, inventory_valuations, gr_ir_matches, inventory_nrv_reviews, inventory_nrv_lines | generalledger, journal | accountspayable (GR/IR invoices), generalledger, journal |
| **closing** | Close calendar | close_tasks, close_task_completions | generalledger, treasury, financialreporting | accountspayable, accountsreceivable, financialreporting, generalledger, integrations, journal, treasury |
| **financialreporting** | Financial statements, Report jobs, Ad hoc reports, Management reports | report_templates, report_template_lines, report_jobs, report_schedules, report_definitions | accountspayable, accountsreceivable, budgeting, fixedassets, generalledger, inventoryaccounting, treasury | the same, plus journal and identity |
| **approvals** | Inbox, Approval limits | approval_limits | accountspayable, budgeting, closing, journal, treasury | every module with a document workflow |
| **dashboard** | Dashboard | — | accountspayable, accountsreceivable, approvals, budgeting, treasury | the same, plus generalledger, journal, tickets |
| **migration** | Migration batches, Parallel run | migration_batches, migration_batch_rows, parallel_runs, parallel_run_lines | generalledger, journal, documents | accountspayable, accountsreceivable, fixedassets, generalledger, inventoryaccounting, journal |
| **integrations** | Integrations, Inbound files, API access | api_clients, webhook_subscriptions, webhook_deliveries, webhook_cursor, integration_transmissions, integration_attempts, interface_batches | journal, budgeting, fixedassets | accountsreceivable, audit, budgeting, fixedassets, generalledger, identity, tax |
| **dataexport** | Data export, Records retention | data_exports, legal_holds | documents | every module (read-only snapshot) |
| **tickets** | Tickets, Service levels | tickets, ticket_events, ticket_categories, sla_policies, business_holidays | journal, documents | identity |
| **organization** | Organisation | organizations, offices, departments, user_office_assignments | — | generalledger (cost centres), identity |
| **identity** | Users, Roles, Access grants, Access reviews | users, roles, role_permissions, user_roles, user_sessions, refresh_tokens, mfa_methods, password_reset_tokens, role_grant_requests, access_review_cycles, access_reviews | — | — |

**Writes to another module's tables (by design):** migration loads opening balances into payables, receivables,
fixed assets and inventory (`supplier_invoices`, `billing_documents`, `receipts`, `fixed_assets`, `inventory_*`);
receivables creates concessionaire users (`users`); treasury records check status in payables' `check_register`;
the development seeders (`config.dev`) load placeholder and official setup data. Every other module writes only
its own tables and changes others through their services.

## 2. Platform modules

| Module | Role | Used by |
|---|---|---|
| **security** | Sign-in, tokens, MFA, passkeys, API client authentication, rate limits | every module |
| **audit** | Hash-chained audit trail, security events, anomaly detection (T2) | every module |
| **notifications** | In-app notifications, e-mail outbox | most modules |
| **documents** | Supporting documents (encrypted at rest) | journal, payables, receivables, treasury, tickets, migration, dataexport |
| **common** | Errors, money, CSV, paging, idempotency, encryption helpers | every module |
| **config** | Spring configuration; development seeders (`config.dev`) | — |

## 3. The data each dropdown offers

Every dropdown is searchable (type any part of a code or name). The records it offers are capped to what its module
may use; the server refuses anything else, so the cap holds for API calls too.

### Accounts (server rule: `AccountPurpose`, always active and postable)

| Field | Module | Offers |
|---|---|---|
| Journal voucher lines | journal | any postable account |
| Supplier invoice lines | accountspayable | expenses; assets bought from a supplier (not cash, receivables or financial assets, no sub-ledger); liabilities the invoice settles without a sub-ledger (GR/IR, accruals) |
| Billing lines, contract revenue account | accountsreceivable | revenue accounts |
| Budget allocations | budgeting | budget-controlled accounts |
| Overhead allocation sender and receiver | journal | expense accounts |
| Bank account's GL account, cash on hand, disposal proceeds | treasury, fixedassets | cash accounts (statement line `-CASH`) without a sub-ledger |
| Stock item inventory / consumption account | inventoryaccounting | inventory accounts (line `-INV`) / expense accounts |
| Asset category asset / accumulated / expense account | fixedassets | asset lines PPE, IP, INT, ROU with a debit balance / the same lines with a credit balance / expense accounts |
| AUC project account | fixedassets | asset accounts with an OTHER (per-project) sub-ledger |
| Asset acquisition offset | fixedassets | accounts without a sub-ledger |
| Open-item management | generalledger | asset and liability accounts |
| Year-end closing surplus account | journal | equity accounts |
| Tax code account | tax | input VAT: asset; EWT, output VAT, final VAT: liability (no sub-ledger) |
| Control-account mappings (Finance settings) | generalledger | AP: liability with supplier sub-ledger; AR: asset with customer sub-ledger; unapplied collections: liability with customer sub-ledger; supplier advances: asset with supplier sub-ledger; cash on hand: cash; creditable WHT and input VAT: asset; output VAT and GR/IR: liability; disposal gain: revenue; disposal loss, inventory variance and revaluation: expense |
| Report and ledger filters | financialreporting, generalledger | every postable account (history included) |

### Other records

| Records | In forms that create or change records | In filters and reports |
|---|---|---|
| Cost centres | active only | all |
| Bank accounts | active only | all |
| Tax codes | active, of the field's type (input/output VAT, EWT) | all |
| Asset categories, ticket categories, AUC projects | active / open only | all |
| Customers, suppliers, stock items | active; searched on the server (first page of matches) | — |

## 4. How this was produced

`scripts/docs/module-map.py` walks `backend/src/main/java/com/miaa/cas`: `import com.miaa.cas.<module>`
gives the calls; `INSERT INTO`, `UPDATE`, `DELETE FROM` and `@Table` give the tables a module writes, and the module
writing a table most is its owner; `FROM` and `JOIN` give the data it reads. Two owners were corrected by hand
(`inventory_movements` to inventory, `supplier_advances` to payables, both also loaded by migration).
