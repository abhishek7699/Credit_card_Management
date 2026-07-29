package com.creditcard.settlement.exception;

public class AlreadySettledException extends RuntimeException {

    private final String transactionId;

    public AlreadySettledException(String transactionId) {
        super("Transaction already settled: " + transactionId);
        this.transactionId = transactionId;
    }

    public String getTransactionId() {
        return transactionId;
    }
}
