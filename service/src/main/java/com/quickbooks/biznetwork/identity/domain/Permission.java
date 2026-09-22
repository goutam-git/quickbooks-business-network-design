package com.quickbooks.biznetwork.identity.domain;

public enum Permission {
    VIEW,
    MANAGE,
    ADMIN;

    /** Ordinal-based >= check: ADMIN > MANAGE > VIEW. */
    public boolean atLeast(Permission required) {
        return this.ordinal() >= required.ordinal();
    }
}
