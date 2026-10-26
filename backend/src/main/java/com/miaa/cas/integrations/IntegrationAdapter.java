package com.miaa.cas.integrations;

import java.util.UUID;

/**
 * An outbound channel. Adapters only deliver; queuing, idempotency, retries and the log are the
 * framework's job. An adapter must be safe to call again with the same transmission, since a crash
 * between delivery and recording can repeat a send; the idempotency key is passed for that reason.
 */
public interface IntegrationAdapter {

    enum Outcome { SENT, ACKNOWLEDGED, RETRYABLE, REJECTED }

    record Transmission(UUID id, String channel, String documentType, UUID documentId, String idempotencyKey, String payloadJson,
            int attempt) {
    }

    record Result(Outcome outcome, String endpoint, Integer httpStatus, String responseExcerpt, String externalReference) {
    }

    String channel();

    /** Human-readable mode, shown on the integrations page. */
    String mode();

    /** True when deliveries are simulated and nothing leaves the system. */
    boolean simulated();

    /** Throws {@code ApiException} with the reason when the channel may not be used. */
    void checkEnabled();

    Result send(Transmission transmission);
}
