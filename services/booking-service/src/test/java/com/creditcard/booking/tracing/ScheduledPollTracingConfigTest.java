package com.creditcard.booking.tracing;

import com.creditcard.booking.service.CheckoutAttemptSweepService;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ScheduledPollTracingConfigTest {

    @Test
    void skipsTheCheckoutAttemptSweepOnly() throws Exception {
        var predicate = new ScheduledPollTracingConfig().skipScheduledPollObservations();
        CheckoutAttemptSweepService sweep = mock(CheckoutAttemptSweepService.class);

        assertThat(predicate.test("sweep", new ScheduledTaskObservationContext(sweep,
                CheckoutAttemptSweepService.class.getMethod("expireStuckCheckoutAttempts")))).isFalse();
        // Another scheduled method keeps its span.
        assertThat(predicate.test("other", new ScheduledTaskObservationContext(sweep,
                Object.class.getMethod("toString")))).isTrue();
        // Observations that are not scheduled jobs are never affected.
        assertThat(predicate.test("request", new Observation.Context())).isTrue();
    }
}
