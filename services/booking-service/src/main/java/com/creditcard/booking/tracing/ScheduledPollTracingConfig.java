package com.creditcard.booking.tracing;

import com.creditcard.booking.service.CheckoutAttemptSweepService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Stops the checkout-attempt sweep from opening a span on every 30-second run, which is almost
 * always empty and would bury the real request traces in Zipkin. Nothing is lost when the sweep
 * does expire an attempt: the work it triggers is still traced through the request spans it creates.
 */
@Configuration
public class ScheduledPollTracingConfig {

    static final Set<String> POLLING_JOBS = Set.of(
            CheckoutAttemptSweepService.class.getName() + "#expireStuckCheckoutAttempts");

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
