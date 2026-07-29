package com.creditcard.transaction.domain;

public enum TransactionStatus {

    PENDING,
    AUTHORIZED,
    DECLINED,
    FAILED;

    public boolean isTerminal() {
        return this == AUTHORIZED || this == DECLINED || this == FAILED;
    }
}
