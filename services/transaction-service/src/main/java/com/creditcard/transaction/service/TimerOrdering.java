package com.creditcard.transaction.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Startup check for the 013 timer ordering (see specs/013-transaction-pending-timeout/design.md).
 * The values are the timers in seconds; {@code R = 2M + b} is the status-retrieve window.
 *
 * <ol>
 *   <li>P ≥ K + M + R + m: the sweep never times out a transaction while an AUTHORIZE can still be in flight.</li>
 *   <li>P + m + d ≤ S: a transaction times out before booking's checkout sweep expires the attempt.
 *       Booking-service checks this one; {@link #checkBookingWindow} is the shared definition.</li>
 *   <li>I ≤ P: the sweep runs at least once per timeout window.</li>
 * </ol>
 * A violation fails startup, so a bad timer combination never runs.
 */
@Component
public class TimerOrdering {

    public TimerOrdering(@Value("${transaction.timeouts.pending-timeout-seconds:300}") long pendingTimeoutSeconds,
                         @Value("${transaction.timeouts.sweep-interval-ms:30000}") long sweepIntervalMs,
                         @Value("${transaction.timeouts.max-delivery-lag-seconds:60}") long maxDeliveryLagSeconds,
                         @Value("${transaction.timeouts.mastercard-read-timeout-seconds:10}") long readTimeoutSeconds,
                         @Value("${transaction.timeouts.status-retrieve-buffer-seconds:2}") long retrieveBufferSeconds,
                         @Value("${transaction.timeouts.margin-seconds:60}") long marginSeconds) {
        checkTransactionRules(pendingTimeoutSeconds, sweepIntervalMs / 1000,
                maxDeliveryLagSeconds, readTimeoutSeconds, retrieveBufferSeconds, marginSeconds);
    }

    /** Rules 1 and 3, the ones transaction-service can check from its own configuration. */
    public static void checkTransactionRules(long p, long i, long k, long m1, long b, long m) {
        long r = 2 * m1 + b;
        long required = k + m1 + r + m;
        if (p < required) {
            throw new IllegalStateException("timer rule 1 violated: P (" + p + " s) must be >= K + M + R + m ("
                    + required + " s)");
        }
        if (i > p) {
            throw new IllegalStateException("timer rule 3 violated: I (" + i + " s) must be <= P (" + p + " s)");
        }
    }

    /** Rule 2: P + m + d ≤ S. Shared definition for booking-service's startup check. */
    public static void checkBookingWindow(long p, long m, long d, long s) {
        if (p + m + d > s) {
            throw new IllegalStateException("timer rule 2 violated: P + m + d (" + (p + m + d)
                    + " s) must be <= S (" + s + " s)");
        }
    }
}
