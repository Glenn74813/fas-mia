# 06 — Accounting Transaction Lifecycle and Double-Entry Posting Design (Phase 2)

> Implemented in Phase 2. Differences from the original design: review and approval are
> configurable steps of one SUBMITTED state (`current_step_order`) rather than separate REVIEWED
> status values; reversals are linked from the reversing entry (`reversal_of_entry_id`) so the
> original entry row is never updated; the trial balance reads posted lines directly and shows any
> unclosed prior-year result explicitly. Code: `com.miaa.cas.journal.PostingService`,
> `VoucherValidator`, `JournalVoucherService`; migration `V7__general_ledger.sql`.

## 1. Journal Entry Voucher (JEV) states

```
DRAFT ──submit──► SUBMITTED ──review ok──► REVIEWED ──approve──► APPROVED ──post──► POSTED ──reverse──► REVERSED*
  ▲  │                 │                       │                    │
  │  └─cancel─► CANCELLED   return──► RETURNED (editable, back to DRAFT on edit)
  │                       reject──► REJECTED (terminal)
  └──────────── correct ◄── RETURNED
* REVERSED is a flag on the original; the reversal is a new POSTED entry linked by reversal_of_id.
```

Each transition: permission check, SoD check (actor ∉ {preparer} for review/approve/post
when the rule says so), optimistic version check, audit event with before/after status, and
approval-decision row (append-only).

## 2. Validation before posting (`PostingValidator`)

1. ≥ 2 lines; each line exactly one of debit/credit > 0; `NUMERIC(19,2)`, HALF_EVEN.
2. Σdebit = Σcredit in transaction currency **and** in PHP functional currency (FX lines carry rate + converted amounts; rounding difference posted to a configured rounding account only within tolerance ≤ ₱0.01 × line count, else reject).
3. Every account exists, is active on the accounting date, is posting-eligible (leaf), and its required dimensions (cost center, subsidiary ledger party) are present.
4. Accounting period for the accounting date is OPEN (or ADJUSTMENT-open and the actor holds `PERIOD_ADJUST_POST`).
5. Required supporting documents for the document type are attached.
6. Source `(source_type, source_id)` not already posted (unique index) — duplicate posting impossible.
7. Budget check where the account is budget-controlled (Phase 3 hook).

## 3. Posting algorithm (single DB transaction, `SERIALIZABLE` not required)

```
BEGIN;                                  -- @Transactional(isolation = READ_COMMITTED)
  SELECT … FROM journal_vouchers WHERE id=? FOR UPDATE;          -- serialise on voucher
  check status = APPROVED, version matches, idempotency key unused
  SELECT … FROM accounting_periods WHERE id=? FOR SHARE;         -- period cannot close mid-post
  validate (section 2)
  next number := numbering_sequences row FOR UPDATE (gap-free per doc type/fiscal year)
  INSERT journal_entries (+ lines)       -- immutable; trigger forbids UPDATE/DELETE
  UPSERT ledger_balances (account, period, dims) += amounts      -- derived, rebuildable
  UPDATE journal_vouchers SET status=POSTED, posted_entry_id, version=version+1
  INSERT idempotency_keys (key, request_hash, response)
  INSERT audit_events (hash-chained)     -- same transaction
  INSERT notification_outbox / integration_events (if any)
COMMIT;
```

A deferred constraint trigger on `journal_entry_lines` re-checks Σdebit = Σcredit per entry at
commit, so even a defective service cannot commit an unbalanced entry.

`ledger_balances` is a cache: `POST /api/v1/general-ledger/balances/rebuild` (restricted)
recomputes it from posted lines and reports differences; reports read posted lines or
verified balances, never dashboard tables.

## 4. Corrections

Posted entries are never edited. `JOURNAL_REVERSE` creates a mirror entry (debits↔credits)
in an open period, linked by `reversal_of_id`, with its own approval chain; the original
keeps status POSTED and gains `reversed_by_id`.

## 5. Period management

`fiscal_years` → `accounting_periods` (12 monthly + optional 13th adjustment period). States:
FUTURE → OPEN → SOFT_CLOSED (only adjustments by authorised users) → CLOSED. Reopen requires
`PERIOD_REOPEN`, a reason, approval by a second user, and is audited. Close checklist
(sub-ledger reconciliation, unposted vouchers, bank reconciliations, depreciation run) must be
green or explicitly waived by `PERIOD_CLOSE` holder with justification.

## 6. Concurrency

* Voucher row lock prevents double posting; idempotency key returns the original response on retry.
* Numbering uses a locked counter row (gap-free, required for official vouchers); contention
  is per document type, acceptable at target volumes.
* Budget reservations (Phase 3) lock the budget allocation row `FOR UPDATE` before checking availability.
