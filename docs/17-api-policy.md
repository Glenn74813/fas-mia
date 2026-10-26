# 17 — API conventions, compatibility and deprecation policy

Status: adopted for development (gap 5 of the specification review, docs/16 §3). The CAS API is consumed today by
the CAS single-page app, the performance kit (`perf/`) and the DR and UAT scripts. Interfaces to MIAA systems and
government channels (C-11, C-16) will consume it later. This policy is what those consumers can rely on.

## 1. Versioning and compatibility

* Every endpoint is under `/api/v1`. The OpenAPI document at `/v3/api-docs` (Swagger UI at `/swagger-ui.html`) is
  the contract. Both are on by default in development; in production they are off unless `CAS_OPENAPI_ENABLED` is set.
* **Compatible changes**, which can ship in any release:
  * new endpoints;
  * new optional request fields or query parameters;
  * new response fields;
  * new enum values in a response, where the field is documented as open;
  * relaxed validation;
  * new error codes for new situations.

  Clients must ignore response fields they do not know.
* **Breaking changes**:
  * removing or renaming an endpoint, a field or a parameter;
  * making something optional required;
  * changing a type or the meaning of a value;
  * tightening validation;
  * changing a response shape (for example an array becoming a page).

  After go-live, a breaking change needs either a new endpoint alongside the old one or `/api/v2` for the affected
  resources, plus the deprecation procedure in §2.
* **Before go-live** there are no external consumers. Breaking changes are allowed when the SPA, the perf kit and the
  scripts are updated in the same change and the release notes say so. Gap 5 was one: 23 collections became pages,
  and four money-moving endpoints began requiring `Idempotency-Key`.

## 2. Deprecation

1. The endpoint is annotated `@ApiDeprecation(since, sunset, successor)` and `@Deprecated`. From then on every
   response carries:
   * `Deprecation: @<unix time>` (RFC 9745), the date it was deprecated;
   * `Sunset: <HTTP date>` (RFC 8594), the earliest date it may be removed;
   * `Link: <successor>; rel="successor-version"`, when a replacement exists.

   The OpenAPI document marks the operation deprecated.
2. **Notice period.** After go-live, the sunset is at least **six months** after deprecation, so external consumers
   can plan a release. Before go-live it may be shorter.
3. Each call to a deprecated endpoint is logged (`DeprecationHeaders`, INFO, with method and path), so the remaining
   callers can be found and contacted.
4. The release notes list new deprecations and upcoming sunsets.
5. Removal happens only after the sunset date **and** once the logs show no calls for 30 days. Removal is a breaking
   change and appears in the release notes.

| Endpoint | Deprecated | Sunset | Use instead |
|---|---|---|---|
| `GET /api/v1/journal-vouchers/approval-queue` | 2026-10-23 | 2027-04-30 | `GET /api/v1/inbox` (all document types; filter `documentType = JOURNAL_VOUCHER`) |

## 3. Collections and paging

* A collection that grows with transactions or master data is **paged**:
  `GET …?page=0&size=25` returns `{ items, page, size, totalItems, totalPages }`.
  * `page` counts from 0. `size` defaults to 25 and may be at most 200; anything else is a 400.
  * Results come from the database page by page (LIMIT/OFFSET on a deterministic order ending in the row id),
    never by loading everything and slicing.
  * `totalItems` is exact.
* Searchable master data takes `q`, a case-insensitive "contains" search in which `%` and `_` are literal. This applies
  to customers, suppliers, inventory items, subsidiary accounts and fixed assets. Forms choose these records through a
  search picker, never a full list.
* Some list endpoints are deliberately **not paged**. Each returns an array bounded by what it is:

| Kind | Endpoints | Why an array |
|---|---|---|
| Configuration | roles, permissions, SoD rules, tax codes, bank accounts, account mappings, cost centres, asset categories, AUC projects, warehouses, fiscal years, report templates, SLA policies, integration channels | Small sets administered by hand, used whole in forms |
| Children of one record | a voucher's documents, a budget's adjustments, a customer's contracts and portal users, a supplier's bank accounts, a period's history | Bounded by the parent record |
| Work sets that must be complete | undeposited receipts (to build a deposit), DVs eligible for a payment file, a customer's open items (to allocate a receipt), pending period-reopen requests, the caller's report schedules | The task needs every item at once. The sets are cleared by daily work, and their size is part of the performance targets (MS §17) |
| Report outputs | check register, SL-to-GL reconciliation, balance-cache differences, SLA summary | One report result, which can also be exported as a report job |

  The approvals inbox (`/inbox`) is not paged either. It caps each source at 200 items (500 for journal vouchers),
  says when it did, and takes `q` so any waiting document can be found.

## 4. Idempotent requests

* Operations whose repetition would move money or post twice require an `Idempotency-Key` header: 8–100 characters
  of letters, digits, `-` and `_` (a UUID is ideal).
* A key belongs to (user, operation). A retry with the same key and the same request returns the stored response
  (same status and body) without acting again. The same key with a different request is a 422
  `IDEMPOTENCY_KEY_REUSED`. A missing or malformed key is a 400.
* The key is locked for the duration of the request, so two concurrent retries cannot both act. The stored response
  and the business effect are committed in one transaction: a failed request stores nothing and may be retried with
  the same key.
* Clients create the key once per user action, reuse it for every retry of that action, and discard it after success.
  The SPA keeps it in a ref for the life of the form.

| Operation | Endpoint |
|---|---|
| Post a journal voucher | `POST /journal-vouchers/{id}/post` |
| Record a collection (receipt) | `POST /receipts` |
| Apply a receipt to invoices (allocation) | `POST /receipts/{id}/applications` |
| Payment instruction (check or ADA) | `POST /disbursement-vouchers/{id}/payment-instruction` |
| ADA payment file (batch of instructions) | `POST /payment-files` |

Other state changes are protected by optimistic versions: a retry fails with 409 `STALE_VERSION` rather than acting
twice (409 `CONCURRENT_MODIFICATION`). The stored keys are kept with the audit evidence; their retention follows C-05.

## 5. Rate limits

* Every API call counts against a limit per signed-in user, or per client address when unauthenticated: 600 and 120
  per minute by default (`CAS_API_REQUESTS_PER_MINUTE`, `CAS_ANONYMOUS_REQUESTS_PER_MINUTE`).
* The count is kept in the database, so it is one limit across all application instances.
* The sign-in endpoints have their own stricter limit per address.
* Responses carry `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset`. A refused call is a 429 problem
  response with `Retry-After`. Clients wait that long rather than retrying at once.
* If the counter store is unavailable, calls are allowed and the failure is logged.

## 6. Errors, tracing and security

* Errors are RFC 9457 problem details with a stable `code` and the request's correlation id. Validation errors list
  each violation.
* Every response carries the correlation id. The user never sees stack traces or SQL.
* Authorisation is enforced on the server for every endpoint, by permission and, for documents, by ownership.
  Concessionaires hold only portal permissions, so internal endpoints refuse them. A record the caller may not see is
  reported as not found.
