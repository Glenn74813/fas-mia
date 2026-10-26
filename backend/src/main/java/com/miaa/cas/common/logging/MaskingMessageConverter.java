package com.miaa.cas.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/** {@code %m} for the plain-text console format, with {@link LogMasker} applied. */
public class MaskingMessageConverter extends MessageConverter {

    /** Development-only output that must stay readable (the logged e-mails of the dev profile). Never used in production code paths. */
    public static final Marker UNMASKED_DEV = MarkerFactory.getMarker("UNMASKED_DEV");

    @Override
    public String convert(ILoggingEvent event) {
        String message = super.convert(event);
        if (event.getMarkerList() != null && event.getMarkerList().contains(UNMASKED_DEV)) {
            return message;
        }
        return LogMasker.mask(message);
    }
}
