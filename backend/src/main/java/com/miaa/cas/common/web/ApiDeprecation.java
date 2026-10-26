package com.miaa.cas.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint as deprecated under the API deprecation policy (docs/17-api-policy.md). Every response then carries
 * {@code Deprecation} (RFC 9745), {@code Sunset} (RFC 8594) and, when there is one, a {@code Link} to the successor.
 * Dates are ISO calendar dates, read as midnight UTC. Also annotate the method {@code @Deprecated} so the OpenAPI
 * document flags it.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiDeprecation {

    /** The date the endpoint was deprecated. */
    String since();

    /** The earliest date it may be removed: at least six months after {@link #since()}. */
    String sunset();

    /** The path of the endpoint that replaces it, if any. */
    String successor() default "";
}
