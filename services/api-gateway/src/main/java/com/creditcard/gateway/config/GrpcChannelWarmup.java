package com.creditcard.gateway.config;

import io.grpc.Channel;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;

/**
 * Actively warms up the transaction-service gRPC channel after application start.
 *
 * <p>In Docker Compose the api-gateway boots before transaction-service's gRPC listener is
 * ready (dependency condition is {@code service_started}, not {@code service_healthy}). The
 * {@code static://} channel fails its first connection attempts and enters grpc-java's
 * exponential reconnect backoff (up to two minutes between attempts), so a request arriving
 * well after transaction-service is healthy can still fast-fail {@code UNAVAILABLE} and be
 * mapped to a 503 fallback.
 *
 * <p>This component runs a short-lived daemon thread that repeatedly calls
 * {@link ManagedChannel#getState(boolean)} and {@link ManagedChannel#resetConnectBackoff()}
 * until the channel reports {@link ConnectivityState#READY}. It never blocks context refresh
 * or readiness and never touches the request path, so a genuine outage still fast-fails to
 * the circuit breaker. The {@code test} profile sets a near-zero warm-up timeout so the loop
 * exits after a single probe.
 *
 * <p>net.devh injects the channel wrapped in one {@code ClientInterceptors} layer per global
 * interceptor (tracing, metrics), so {@link #resolveManagedChannel()} unwraps the delegate
 * chain to reach the {@link ManagedChannel}; if it cannot, the warm-up is skipped and normal
 * on-demand reconnection still applies.
 */
@Component
public class GrpcChannelWarmup {

    private static final Logger log = LoggerFactory.getLogger(GrpcChannelWarmup.class);

    @GrpcClient("transaction-service")
    private Channel channel;

    private final Duration timeout;
    private final Duration pollInterval;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Executor executor;

    @Autowired
    public GrpcChannelWarmup(
            @Value("${gateway.grpc.warmup.timeout:120s}") Duration timeout,
            @Value("${gateway.grpc.warmup.poll-interval:2s}") Duration pollInterval) {
        this(timeout, pollInterval, Clock.systemUTC(), Sleeper.real(), runnable -> {
            Thread thread = new Thread(runnable, "grpc-transaction-warmup");
            thread.setDaemon(true);
            thread.start();
        });
    }

    GrpcChannelWarmup(Duration timeout, Duration pollInterval,
                      Clock clock, Sleeper sleeper, Executor executor) {
        this.timeout = timeout;
        this.pollInterval = pollInterval;
        this.clock = clock;
        this.sleeper = sleeper;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        executor.execute(this::warmUp);
    }

    void warmUp() {
        ManagedChannel managedChannel = resolveManagedChannel();
        if (managedChannel == null) {
            log.warn("Could not resolve the underlying ManagedChannel for transaction-service; "
                    + "skipping gRPC warm-up");
            return;
        }
        Instant deadline = clock.instant().plus(timeout);
        while (true) {
            ConnectivityState state = managedChannel.getState(true);
            if (state == ConnectivityState.READY) {
                log.info("gRPC channel transaction-service READY; warm-up complete");
                return;
            }
            if (state == ConnectivityState.SHUTDOWN) {
                log.warn("gRPC channel transaction-service SHUTDOWN; abandoning warm-up");
                return;
            }
            if (!clock.instant().isBefore(deadline)) {
                log.warn("gRPC channel transaction-service not READY after {}; abandoning warm-up", timeout);
                return;
            }
            managedChannel.resetConnectBackoff();
            if (!sleeper.sleep(pollInterval)) {
                log.warn("gRPC channel transaction-service warm-up interrupted");
                return;
            }
        }
    }

    /** Unwraps net.devh's {@code ClientInterceptors} delegate chain to the raw {@link ManagedChannel}. */
    private ManagedChannel resolveManagedChannel() {
        Channel current = channel;
        while (current != null && !(current instanceof ManagedChannel)) {
            current = delegateOf(current);
        }
        return (ManagedChannel) current;
    }

    private static Channel delegateOf(Channel wrapper) {
        try {
            Field field = wrapper.getClass().getDeclaredField("channel");
            field.setAccessible(true);
            return (Channel) field.get(wrapper);
        } catch (Exception e) {
            return null;
        }
    }

    /** Sleep seam so {@link #warmUp()} needs no real time in unit tests. */
    interface Sleeper {

        /** @return {@code true} if it slept the full duration, {@code false} if interrupted. */
        boolean sleep(Duration duration);

        static Sleeper real() {
            return duration -> {
                try {
                    Thread.sleep(duration.toMillis());
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            };
        }
    }
}
