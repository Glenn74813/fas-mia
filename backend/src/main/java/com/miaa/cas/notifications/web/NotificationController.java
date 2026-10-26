package com.miaa.cas.notifications.web;

import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.notifications.NotificationService.Notification;
import com.miaa.cas.notifications.NotificationService.UnreadCount;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own in-app notifications (internal users and concessionaires alike). */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public PageResponse<Notification> mine(@RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return notifications.mine(unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public UnreadCount unread() {
        return notifications.unread();
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable UUID id) {
        notifications.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public UnreadCount readAll() {
        return notifications.markAllRead();
    }
}
