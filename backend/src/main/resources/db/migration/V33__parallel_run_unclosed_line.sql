-- The trial balance carries the unclosed result of prior fiscal years (year-end closing not yet posted) as a row
-- without an account. A parallel run on the closing basis shows it as a CAS-only line coded UNCLOSED, which must be
-- explained (or the year-end close posted) before sign-off. Every other non-unmapped line still needs an account.

ALTER TABLE parallel_run_lines DROP CONSTRAINT ck_prl_account;
ALTER TABLE parallel_run_lines ADD CONSTRAINT ck_prl_account CHECK (
    (line_type = 'UNMAPPED' AND account_id IS NULL)
    OR (line_type <> 'UNMAPPED' AND (account_id IS NOT NULL OR (line_type = 'CAS_ONLY' AND account_code = 'UNCLOSED'))));
