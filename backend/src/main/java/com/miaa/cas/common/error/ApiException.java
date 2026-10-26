package com.miaa.cas.common.error;

import java.util.Map;

/** Business or security failure with a safe, user-facing message and a stable error code. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> properties;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> properties) {
        super(message);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public static ApiException notFound(String resource) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, resource + " not found");
    }

    public static ApiException staleVersion() {
        return new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                "The record was changed by another user. Reload it and try again.");
    }
}
