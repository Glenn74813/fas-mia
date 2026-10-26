package com.miaa.cas.common.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/** Stack traces for the plain-text console format ({@code %wEx}), with {@link LogMasker} applied to the messages in them. */
public class MaskingThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return LogMasker.mask(super.throwableProxyToString(tp));
    }
}
