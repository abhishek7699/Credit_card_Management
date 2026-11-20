package com.creditcard.transaction.tracing;

import com.creditcard.transaction.repository.OutboxRepository;
import com.creditcard.transaction.service.OutboxPublisher;
import com.creditcard.transaction.service.OutboxRelay;
import com.creditcard.transaction.service.TransactionTimeoutSweep;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduledPollTracingConfigTest {

    @Test
    void relayPollWithNothingToSendOpensNoObservation() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ObservationRegistry registry = registryWithPredicate(handler, true);

        runRelayPoll(registry, emptyRelay());

        assertThat(handler.started).isEmpty();
    }

    @Test
    void relayPollIsObservedWhenNoPredicateIsRegistered() throws Exception {
        // Control for the test above: proves the harness does record the scheduled poll
        // when nothing filters it, so "no observation" really comes from the predicate.
        RecordingHandler handler = new RecordingHandler();
        ObservationRegistry registry = registryWithPredicate(handler, false);

        runRelayPoll(registry, emptyRelay());

        assertThat(handler.started).hasSize(1);
    }

    @Test
    void predicateSkipsOnlyThePollingJobs() throws Exception {
        var predicate = new ScheduledPollTracingConfig().skipScheduledPollObservations();
        OutboxRelay relay = emptyRelay();

        assertThat(predicate.test("poll", new ScheduledTaskObservationContext(relay,
                OutboxRelay.class.getMethod("relayPending")))).isFalse();
        assertThat(predicate.test("sweep", new ScheduledTaskObservationContext(
                mock(TransactionTimeoutSweep.class), TransactionTimeoutSweep.class.getMethod("sweepScheduled"))))
                .isFalse();
        // A scheduled job that is not a poll keeps its span.
        assertThat(predicate.test("batch", new ScheduledTaskObservationContext(relay,
                OutboxRelay.class.getMethod("relayBatch")))).isTrue();
        // Observations that are not scheduled jobs are never affected.
        assertThat(predicate.test("request", new Observation.Context())).isTrue();
    }

    private static OutboxRelay emptyRelay() {
        OutboxRepository repository = mock(OutboxRepository.class);
        when(repository.findTop100BySentAtIsNullAndFailedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of());
        return new OutboxRelay(repository, mock(OutboxPublisher.class), new SimpleMeterRegistry(), 5, true);
    }

    private static void runRelayPoll(ObservationRegistry registry, OutboxRelay relay) throws Exception {
        // Spring wraps every @Scheduled method this way, so this is the same path a real poll takes.
        new ScheduledMethodRunnable(relay, OutboxRelay.class.getMethod("relayPending"),
                "outboxRelay.relayPending", () -> registry).run();
    }

    private static ObservationRegistry registryWithPredicate(RecordingHandler handler, boolean withPredicate) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);
        if (withPredicate) {
            registry.observationConfig().observationPredicate(
                    new ScheduledPollTracingConfig().skipScheduledPollObservations());
        }
        return registry;
    }

    private static final class RecordingHandler implements ObservationHandler<Observation.Context> {
        final List<String> started = new ArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStart(Observation.Context context) {
            started.add(String.valueOf(context));
        }
    }
}
