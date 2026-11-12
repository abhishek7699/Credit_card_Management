package com.creditcard.authorization.domain;

public enum AuthorizationOutcome {
    AUTHORIZED, DECLINED, FAILED,
    /** 013: an AUTHORIZED deduction whose credit was given back after its transaction timed out. */
    RELEASED;

    public boolean isApproved() {
        return this == AUTHORIZED;
    }
}
