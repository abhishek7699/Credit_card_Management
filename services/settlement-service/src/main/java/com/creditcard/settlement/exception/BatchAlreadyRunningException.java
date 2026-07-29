package com.creditcard.settlement.exception;

public class BatchAlreadyRunningException extends RuntimeException {

    private final String runningBatchId;

    public BatchAlreadyRunningException(String runningBatchId) {
        super("Settlement batch is already running");
        this.runningBatchId = runningBatchId;
    }

    public String getRunningBatchId() {
        return runningBatchId;
    }
}
