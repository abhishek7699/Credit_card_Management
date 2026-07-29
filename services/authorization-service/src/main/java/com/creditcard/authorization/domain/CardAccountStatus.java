package com.creditcard.authorization.domain;

public enum CardAccountStatus {
    ACTIVE, BLOCKED, EXPIRED;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
