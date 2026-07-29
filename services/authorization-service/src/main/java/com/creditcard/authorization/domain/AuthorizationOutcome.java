package com.creditcard.authorization.domain;

public enum AuthorizationOutcome {
    AUTHORIZED, DECLINED, FAILED;

    public boolean isApproved() {
        return this == AUTHORIZED;
    }
}
