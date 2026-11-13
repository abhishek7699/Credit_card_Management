package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.OutboxEvent;

/**
 * A failure caused by one outbox row (013): its stored JSON cannot be read, or the broker rejects
 * this record. Only this row waits on its retry budget. A transport failure (broker unreachable,
 * timeout) is a different exception, and OutboxRelay stops the pass for it instead.
 */
public class OutboxRowException extends RuntimeException {

    public OutboxRowException(OutboxEvent row, String reason, Throwable cause) {
        super("outbox row " + row.getEventId() + ": " + reason, cause);
    }
}
