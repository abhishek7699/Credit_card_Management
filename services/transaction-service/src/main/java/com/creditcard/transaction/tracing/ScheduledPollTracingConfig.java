package com.creditcard.transaction.tracing;

import com.creditcard.transaction.service.OutboxRelay;
import com.creditcard.transaction.service.TransactionTimeoutSweep;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Stops the polling jobs from opening a span on every run. The outbox relay runs every 500 ms and
 * the PENDING sweep every 30 s, and nearly every run finds nothing, so their scheduled-task spans
 * would bury the real request traces in Zipkin.
 *
 * <p>Skipping the poll span does not lose the event's trace: {@link
 * com.creditcard.transaction.service.OutboxPublisher} rebuilds the span from the trace ids stored on
 * the outbox row, so a publish still continues the trace of the request that wrote the row.
 */
@Configuration
public class ScheduledPollTracingConfig {

    static final Set<String> POLLING_JOBS = Set.of(
            OutboxRelay.class.getName() + "#relayPending",
            TransactionTimeoutSweep.class.getName() + "#sweepScheduled");

    @Bean
    ObservationPredicate skipScheduledPollObservations() {
        return (name, context) -> !isPollingJob(context);
    }

    static boolean isPollingJob(Observation.Context context) {
        if (!(context instanceof ScheduledTaskObservationContext task)) {
            return false;
        }
        Method method = task.getMethod();
        return POLLING_JOBS.contains(method.getDeclaringClass().getName() + "#" + method.getName());
    }
}
