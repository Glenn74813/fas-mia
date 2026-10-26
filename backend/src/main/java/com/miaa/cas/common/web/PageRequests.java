package com.miaa.cas.common.web;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Validates paging parameters and maps public sort keys onto a whitelist of entity properties. */
public final class PageRequests {

    public static final int MAX_SIZE = 200;

    private PageRequests() {
    }

    public static Pageable of(int page, int size, String sort, Map<String, String> allowedSorts, String defaultSort) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "page must be >= 0 and size between 1 and " + MAX_SIZE);
        }
        String requested = sort == null || sort.isBlank() ? defaultSort : sort;
        Sort.Direction direction = Sort.Direction.ASC;
        String key = requested;
        if (requested.startsWith("-")) {
            direction = Sort.Direction.DESC;
            key = requested.substring(1);
        }
        String property = allowedSorts.get(key);
        if (property == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported sort key '" + key + "'. Allowed: "
                    + String.join(", ", allowedSorts.keySet()));
        }
        return PageRequest.of(page, size, Sort.by(direction, property));
    }
}
