package com.miaa.cas.security;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class CasAuthentication extends AbstractAuthenticationToken {

    private final CasPrincipal principal;
    private final transient Jwt jwt;

    public CasAuthentication(CasPrincipal principal, Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public CasPrincipal getPrincipal() {
        return principal;
    }
}
