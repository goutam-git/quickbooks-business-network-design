package com.quickbooks.biznetwork.common.exception;

/** Maps to 409. E.g. IDENTITY_NOT_ACTIVE, MERGE_ALREADY_SUPERSEDED. */
public class ConflictException extends RuntimeException {
    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
