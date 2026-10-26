-- The chain trigger assigns event ids under its advisory lock (V3). Dropping the column default stops
-- each insert consuming a second sequence value, so sequence numbers only skip when a transaction
-- rolls back. Gaps are therefore possible and expected; completeness is proven by the hash chain.
ALTER TABLE audit_events ALTER COLUMN id DROP DEFAULT;
ALTER TABLE security_events ALTER COLUMN id DROP DEFAULT;
