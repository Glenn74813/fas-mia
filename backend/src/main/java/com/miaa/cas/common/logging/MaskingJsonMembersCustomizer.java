package com.miaa.cas.common.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Applies {@link LogMasker} to every string value of structured (ECS JSON) log events: message, stack trace and
 * MDC entries. Registered with {@code logging.structured.json.customizer}.
 */
public class MaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogMasker::mask));
    }
}
