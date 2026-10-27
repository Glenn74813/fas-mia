package com.miaa.cas.integrations.bir;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.integrations.IntegrationAdapter;
import java.io.IOException;
import java.net.URI;
import com.miaa.cas.common.crypto.TlsPolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Port to the BIR Electronic Invoicing System. Until BIR's specification and the Permit to Transmit
 * are in hand (C-06) there is no production mode, by design:
 * <ul>
 * <li>DISABLED (default): nothing can be queued.</li>
 * <li>LOOPBACK: deliveries are simulated inside CAS and marked as such; refused when the {@code prod}
 * profile is active.</li>
 * <li>SANDBOX: the CAS canonical document is POSTed to the configured sandbox URL with an
 * {@code Idempotency-Key} header. The payload is not the EIS schema; mapping to it awaits the spec.</li>
 * </ul>
 */
@Component
public class BirEisAdapter implements IntegrationAdapter {

    public static final String CHANNEL = "BIR_EIS";

    public enum Mode { DISABLED, LOOPBACK, SANDBOX }

    private final Mode mode;
    private final String sandboxUrl;
    private final String sandboxToken;
    private final boolean production;
    private final HttpClient http;

    public BirEisAdapter(@Value("${cas.integrations.bir-eis.mode:DISABLED}") Mode mode,
            @Value("${cas.integrations.bir-eis.sandbox-url:}") String sandboxUrl,
            @Value("${cas.integrations.bir-eis.sandbox-token:}") String sandboxToken, Environment env, TlsPolicy tls) {
        this.mode = mode;
        this.sandboxUrl = sandboxUrl;
        this.sandboxToken = sandboxToken;
        this.production = Arrays.asList(env.getActiveProfiles()).contains("prod");
        if (production && mode == Mode.LOOPBACK) {
            throw new IllegalStateException("cas.integrations.bir-eis.mode=LOOPBACK is not allowed with the prod profile");
        }
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER)
                .sslParameters(tls.clientParameters()).build();
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public String mode() {
        return switch (mode) {
            case DISABLED -> "Disabled (awaiting BIR specification and Permit to Transmit, C-06)";
            case LOOPBACK -> "Loopback simulation (nothing leaves CAS)";
            case SANDBOX -> "Sandbox: " + sandboxUrl;
        };
    }

    @Override
    public boolean simulated() {
        return mode != Mode.SANDBOX;
    }

    @Override
    public void checkEnabled() {
        if (mode == Mode.DISABLED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "BIR EIS transmission is disabled until BIR's specification and the Permit to "
                    + "Transmit are available (C-06).");
        }
        if (mode == Mode.SANDBOX && (sandboxUrl.isBlank() || !sandboxUrl.startsWith("https://"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "BIR EIS sandbox mode needs an https sandbox URL (cas.integrations.bir-eis.sandbox-url).");
        }
    }

    @Override
    public Result send(Transmission t) {
        if (mode == Mode.LOOPBACK) {
            return new Result(Outcome.ACKNOWLEDGED, "loopback", null, "Simulated acknowledgement; nothing was transmitted.",
                    "SIMULATED-" + t.id().toString().substring(0, 8).toUpperCase());
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(sandboxUrl)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Idempotency-Key", t.idempotencyKey())
                .POST(HttpRequest.BodyPublishers.ofString(t.payloadJson()));
        if (!sandboxToken.isBlank()) {
            req.header("Authorization", "Bearer " + sandboxToken);
        }
        try {
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            String body = res.body();
            if (code >= 200 && code < 300) {
                return new Result(Outcome.SENT, sandboxUrl, code, body, res.headers().firstValue("Location").orElse(null));
            }
            boolean retry = code >= 500 || code == 408 || code == 429;
            return new Result(retry ? Outcome.RETRYABLE : Outcome.REJECTED, sandboxUrl, code, body, null);
        } catch (IOException e) {
            return new Result(Outcome.RETRYABLE, sandboxUrl, null, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.RETRYABLE, sandboxUrl, null, "Interrupted", null);
        }
    }
}
