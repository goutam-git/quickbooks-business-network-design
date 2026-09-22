package com.quickbooks.biznetwork.common.exception;

/** Maps to 400. E.g. INVALID_DEPTH. */
public class BadRequestException extends RuntimeException {
    private final String code;

    public BadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
