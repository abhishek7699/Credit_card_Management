package com.creditcard.booking.kafka;

/**
 * No {@code CheckoutPaymentAttempt} matches a {@code transaction.status.updated}
 * event's transactionId yet (layer 2c rework — booking creation no longer has its own
 * transactionId space at all; every outcome this consumer sees from the 2026-10-31
 * rework forward is a checkout-attempt outcome, research.md §5). This is deliberately
 * NOT a REST-layer exception (no {@code @ExceptionHandler} maps it) — it is only ever
 * thrown from {@link TransactionStatusConsumer}, where it is expected to propagate out
 * of the listener so the container's error handler retries with bounded backoff
 * (research.md §3a): the attempt row and this event can race, since booking-service
 * persists the attempt PENDING, then calls transaction-service, then saves the
 * returned transactionId as separate steps — not one atomic unit with the event's
 * production on the other side.
 */
public class UnknownTransactionException extends RuntimeException {
    public UnknownTransactionException(String transactionId) {
        super("No checkout payment attempt found for transactionId (yet): " + transactionId);
    }
}
