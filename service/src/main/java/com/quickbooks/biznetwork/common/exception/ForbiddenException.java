package com.quickbooks.biznetwork.common.exception;

/** Maps to 403. Used only where existence must be revealed but action is
 * disallowed (rare in this API — most authorization gaps prefer 404, see
 * NotFoundException javadoc). */
public class ForbiddenException extends RuntimeException {
    private final String code;

    public ForbiddenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
