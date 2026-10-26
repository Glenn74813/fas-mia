-- In-app notifications (MS §8, §12): a per-user feed of outcomes that concern the user, such as a decision on a
-- document they prepared or a report that has finished. Written in the same transaction as the business change.
-- Work waiting for a user is not stored here: the approvals inbox computes it live from each module's own rules.

CREATE TABLE notifications (
    id          UUID PRIMARY KEY,
    user_id     UUID          NOT NULL REFERENCES users (id),
    category    VARCHAR(30)   NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    body        VARCHAR(1000),
    link        VARCHAR(300),
    created_at  TIMESTAMPTZ   NOT NULL,
    read_at     TIMESTAMPTZ
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;
