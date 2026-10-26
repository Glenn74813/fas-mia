package com.miaa.cas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.audit.SecurityEventService;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.error.ProblemFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/** Filter-level 401/403 responses in the same problem format as the controller advice. */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;
    private final SecurityEventService securityEvents;

    public ProblemSecurityHandlers(ObjectMapper mapper, SecurityEventService securityEvents) {
        this.mapper = mapper;
        this.securityEvents = securityEvents;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, ErrorCode.UNAUTHENTICATED, "Authentication is required or the access token is no longer valid.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        securityEvents.accessDenied(request.getMethod() + " " + request.getRequestURI());
        if (ex instanceof CsrfException) {
            write(response, ErrorCode.ACCESS_DENIED, "Missing or invalid CSRF token.");
        } else {
            write(response, ErrorCode.ACCESS_DENIED, "You do not have permission to perform this action.");
        }
    }

    private void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ProblemFactory.create(code, message));
    }
}
