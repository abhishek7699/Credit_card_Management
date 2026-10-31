package com.creditcard.booking.kafka;

/**
 * No booking matches a {@code transaction.status.updated} event's transactionId yet.
 * This is deliberately NOT a REST-layer exception (no {@code @ExceptionHandler} maps
 * it) — it is only ever thrown from {@link TransactionStatusConsumer}, where it is
 * expected to propagate out of the listener so the container's error handler retries
 * with bounded backoff (research.md §3a): the booking row and this event can race,
 * since booking-service persists the booking HELD, then calls transaction-service,
 * then saves the returned transactionId as three separate steps — not one atomic unit
 * with the event's production on the other side.
 */
public class BookingNotFoundForTransactionException extends RuntimeException {
    public BookingNotFoundForTransactionException(String transactionId) {
        super("No booking found for transactionId (yet): " + transactionId);
    }
}
