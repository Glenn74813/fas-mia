package com.miaa.cas.common.error;

import com.miaa.cas.common.web.CorrelationIdFilter;
import java.net.URI;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem documents with the CAS error code and correlation ID. */
public final class ProblemFactory {

    private ProblemFactory() {
    }

    public static ProblemDetail create(ErrorCode code, String detail) {
        return create(code, detail, Map.of());
    }

    public static ProblemDetail create(ErrorCode code, String detail, Map<String, Object> properties) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create("urn:miaa:cas:error:" + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        properties.forEach(problem::setProperty);
        return problem;
    }
}
