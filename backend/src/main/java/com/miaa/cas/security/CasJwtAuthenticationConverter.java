package com.miaa.cas.security;

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

    public CasJwtAuthenticationConverter(CallerResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
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
