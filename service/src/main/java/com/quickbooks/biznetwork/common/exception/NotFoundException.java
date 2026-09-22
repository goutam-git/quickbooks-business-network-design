package com.quickbooks.biznetwork.common.exception;

/** Maps to 404. Used both for genuine absence and for authorization-driven
 * non-disclosure (Section 14: never distinguish "not found" from "unauthorized"). */
public class NotFoundException extends RuntimeException {
    private final String code;

    public NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
