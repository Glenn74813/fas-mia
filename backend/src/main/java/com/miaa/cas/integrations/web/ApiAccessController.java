package com.miaa.cas.integrations.web;

import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.integrations.api.ApiClientService;
import com.miaa.cas.integrations.webhooks.WebhookDispatcher;
import com.miaa.cas.integrations.webhooks.WebhookService;
import com.miaa.cas.security.client.ApiClientAuthenticator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "API access", description = "API clients (OAuth 2.0 client credentials) and webhook subscriptions (TOR §4.6)")
public class ApiAccessController {

    public record ClientRequest(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description, @NotEmpty List<String> scopes,
            Instant expiresAt) {
    }

    public record VersionRequest(long version) {
    }

    public record RevokeRequest(@NotBlank @Size(max = 500) String reason, long version) {
    }

    public record SubscriptionRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 500) String url,
            @NotEmpty List<String> events) {
    }

    public record StatusRequest(@NotBlank String status, long version) {
    }

    private final ApiClientService clients;
    private final WebhookService webhooks;
    private final WebhookDispatcher dispatcher;

    public ApiAccessController(ApiClientService clients, WebhookService webhooks, WebhookDispatcher dispatcher) {
        this.clients = clients;
        this.webhooks = webhooks;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/api-access/catalog")
    @PreAuthorize("hasAnyAuthority('PERM_INTEGRATION_VIEW', 'PERM_API_CLIENT_MANAGE')")
    @Operation(summary = "The scopes an API client may hold and the events a webhook may subscribe to")
    public Map<String, List<String>> catalog() {
        return Map.of("scopes", ApiClientAuthenticator.SCOPES.stream().sorted().toList(), "events", List.copyOf(WebhookService.EVENTS));
    }

    @GetMapping("/api-clients")
    @PreAuthorize("hasAnyAuthority('PERM_INTEGRATION_VIEW', 'PERM_API_CLIENT_MANAGE')")
    public List<ApiClientService.ClientView> clients() {
        return clients.list();
    }

    @PostMapping("/api-clients")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_API_CLIENT_MANAGE')")
    @Operation(summary = "Create an API client; the response carries its secret, which is never shown again")
    public ApiClientService.ClientWithSecret createClient(@Valid @RequestBody ClientRequest r) {
        return clients.create(r.name(), r.description(), r.scopes(), r.expiresAt());
    }

    @PostMapping("/api-clients/{id}/rotate-secret")
    @PreAuthorize("hasAuthority('PERM_API_CLIENT_MANAGE')")
    @Operation(summary = "Give a client a new secret; the old one stops working at once")
    public ApiClientService.ClientWithSecret rotateClient(@PathVariable UUID id, @RequestBody VersionRequest r) {
        return clients.rotate(id, r.version());
    }

    @PostMapping("/api-clients/{id}/revoke")
    @PreAuthorize("hasAuthority('PERM_API_CLIENT_MANAGE')")
    public ApiClientService.ClientView revokeClient(@PathVariable UUID id, @Valid @RequestBody RevokeRequest r) {
        return clients.revoke(id, r.reason(), r.version());
    }

    @GetMapping("/webhooks")
    @PreAuthorize("hasAnyAuthority('PERM_INTEGRATION_VIEW', 'PERM_API_CLIENT_MANAGE')")
    public List<WebhookService.SubscriptionView> subscriptions() {
        return webhooks.list();
    }

    @PostMapping("/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_API_CLIENT_MANAGE')")
    @Operation(summary = "Subscribe a URL to events; the response carries the signing secret, which is never shown again")
    public WebhookService.SubscriptionWithSecret subscribe(@Valid @RequestBody SubscriptionRequest r) {
        return webhooks.create(r.name(), r.url(), r.events());
    }

    @PostMapping("/webhooks/{id}/status")
    @PreAuthorize("hasAuthority('PERM_API_CLIENT_MANAGE')")
    @Operation(summary = "Pause or resume a subscription")
    public WebhookService.SubscriptionView setStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        return webhooks.setStatus(id, r.status(), r.version());
    }

    @PostMapping("/webhooks/{id}/rotate-secret")
    @PreAuthorize("hasAuthority('PERM_API_CLIENT_MANAGE')")
    public WebhookService.SubscriptionWithSecret rotateWebhook(@PathVariable UUID id, @RequestBody VersionRequest r) {
        return webhooks.rotateSecret(id, r.version());
    }

    @PostMapping("/webhooks/{id}/test")
    @PreAuthorize("hasAuthority('PERM_API_CLIENT_MANAGE')")
    @Operation(summary = "Send a PING event now and return the delivery")
    public WebhookService.DeliveryView test(@PathVariable UUID id) {
        return dispatcher.ping(id);
    }

    @GetMapping("/webhooks/{id}/deliveries")
    @PreAuthorize("hasAnyAuthority('PERM_INTEGRATION_VIEW', 'PERM_API_CLIENT_MANAGE')")
    public PageResponse<WebhookService.DeliveryView> deliveries(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return webhooks.deliveries(id, page, size);
    }

    @PostMapping("/webhooks/deliveries/{id}/redeliver")
    @PreAuthorize("hasAuthority('PERM_API_CLIENT_MANAGE')")
    public WebhookService.DeliveryView redeliver(@PathVariable UUID id) {
        return webhooks.redeliver(id);
    }
}
