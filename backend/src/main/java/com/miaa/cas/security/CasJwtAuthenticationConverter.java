package com.miaa.cas.security;

import com.miaa.cas.security.client.ApiClientAuthenticator;
import com.miaa.cas.security.jwt.TokenService;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

@Component
public class CasJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final CallerResolver resolver;
    private final ApiClientAuthenticator clients;

    public CasJwtAuthenticationConverter(CallerResolver resolver, ApiClientAuthenticator clients) {
        this.resolver = resolver;
        this.clients = clients;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String client = jwt.getClaimAsString(TokenService.CLAIM_CLIENT);
        if (client != null) {
            UUID clientId;
            try {
                clientId = UUID.fromString(client);
            } catch (IllegalArgumentException e) {
                throw new InvalidBearerTokenException("Malformed token subject");
            }
            CallerResolver.Caller caller = clients.resolve(clientId)
                    .orElseThrow(() -> new InvalidBearerTokenException("API client is no longer active"));
            return new CasAuthentication(caller.principal(), jwt, caller.authorities());
        }
        UUID userId;
        UUID sessionId;
        String subject = jwt.getSubject();
        String session = jwt.getClaimAsString(TokenService.CLAIM_SESSION);
        if (subject == null || session == null) {
            throw new InvalidBearerTokenException("Malformed token subject");
        }
        try {
            userId = UUID.fromString(subject);
            sessionId = UUID.fromString(session);
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Malformed token subject");
        }
        CallerResolver.Caller caller = resolver.resolve(userId, sessionId)
                .orElseThrow(() -> new InvalidBearerTokenException("Session is no longer valid"));
        return new CasAuthentication(caller.principal(), jwt, caller.authorities());
    }
}
