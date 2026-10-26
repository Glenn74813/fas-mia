-- Cluster-wide API rate limiting (MS §9.4): fixed one-minute windows counted in the database, so every application
-- instance enforces one shared limit. UNLOGGED: counters are short-lived and need no crash safety or replication.
CREATE UNLOGGED TABLE api_rate_windows (
    bucket        VARCHAR(80)  NOT NULL,
    window_start  TIMESTAMPTZ  NOT NULL,
    hits          INTEGER      NOT NULL,
    PRIMARY KEY (bucket, window_start)
);

-- Operational alerts (MS §10): at most one alert per key and quiet period; repeats inside it are counted and reported
-- with the next alert.
CREATE TABLE alert_throttle (
    alert_key     VARCHAR(120) PRIMARY KEY,
    last_sent_at  TIMESTAMPTZ  NOT NULL,
    suppressed    INTEGER      NOT NULL DEFAULT 0
);
