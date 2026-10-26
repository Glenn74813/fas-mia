package com.miaa.cas.notifications;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.security.CurrentActor;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user's in-app notifications. Producers call {@link #notify} inside their own transaction, so a
 * notification exists only if the change it describes was committed. Every read and update is limited to the
 * caller's own rows.
 */
@Service
public class NotificationService {

    public record Notification(UUID id, String category, String title, String body, String link, Instant createdAt, Instant readAt) {
    }

    public record UnreadCount(long unread) {
    }

    private static final int MAX_PAGE_SIZE = 50;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Adds a notification for {@code userId}; nothing is written when there is no recipient or it is the actor themself. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notify(UUID userId, String category, String title, String body, String link) {
        if (userId == null || (CurrentActor.isPresent() && userId.equals(CurrentActor.userId()))) {
            return;
        }
        jdbc.update("INSERT INTO notifications (id, user_id, category, title, body, link, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, category, truncate(title, 200), body == null ? null : truncate(body, 1000), link,
                Timestamp.from(Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public PageResponse<Notification> mine(boolean unreadOnly, int page, int size) {
        UUID me = CurrentActor.userId();
        int s = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int p = Math.max(0, page);
        String filter = unreadOnly ? " AND read_at IS NULL" : "";
        long total = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?" + filter, Long.class, me);
        List<Notification> items = jdbc.query("SELECT id, category, title, body, link, created_at, read_at FROM notifications "
                + "WHERE user_id = ?" + filter + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?", (rs, i) -> new Notification(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getTimestamp(6).toInstant(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()), me, s, (long) p * s);
        return new PageResponse<>(items, p, s, total, (int) ((total + s - 1) / s));
    }

    @Transactional(readOnly = true)
    public UnreadCount unread() {
        return new UnreadCount(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                Long.class, CurrentActor.userId()));
    }

    /** Marks one of the caller's notifications read; another user's notification is reported as not found. */
    @Transactional
    public void markRead(UUID id) {
        int found = jdbc.update("UPDATE notifications SET read_at = COALESCE(read_at, ?) WHERE id = ? AND user_id = ?",
                Timestamp.from(Instant.now(clock)), id, CurrentActor.userId());
        if (found == 0) {
            throw ApiException.notFound("Notification");
        }
    }

    @Transactional
    public UnreadCount markAllRead() {
        jdbc.update("UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL", Timestamp.from(Instant.now(clock)),
                CurrentActor.userId());
        return new UnreadCount(0);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
