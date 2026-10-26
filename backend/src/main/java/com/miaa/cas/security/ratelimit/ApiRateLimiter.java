package com.miaa.cas.security.ratelimit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Counts requests per bucket (a user or a client address) in one-minute windows held in PostgreSQL, so the limit
 * holds across all application instances. One upsert per request, outside any business transaction.
 */
@Component
public class ApiRateLimiter {

    public record Decision(boolean allowed, int limit, int remaining, long resetSeconds) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ApiRateLimiter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Decision hit(String bucket, int limit) {
        Instant now = Instant.now(clock);
        Instant window = now.truncatedTo(ChronoUnit.MINUTES);
        Integer hits = jdbc.queryForObject("""
                INSERT INTO api_rate_windows (bucket, window_start, hits) VALUES (?, ?, 1)
                ON CONFLICT (bucket, window_start) DO UPDATE SET hits = api_rate_windows.hits + 1 RETURNING hits""",
                Integer.class, bucket, Timestamp.from(window));
        int used = hits == null ? 1 : hits;
        long reset = Math.max(1, window.plus(1, ChronoUnit.MINUTES).getEpochSecond() - now.getEpochSecond());
        return new Decision(used <= limit, limit, Math.max(0, limit - used), reset);
    }

    /** Old windows carry no state worth keeping. */
    @Scheduled(fixedDelay = 300_000)
    public void purge() {
        jdbc.update("DELETE FROM api_rate_windows WHERE window_start < ?", Timestamp.from(Instant.now(clock).minus(10, ChronoUnit.MINUTES)));
    }
}
