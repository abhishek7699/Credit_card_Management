package com.creditcard.gateway.config;

import com.creditcard.gateway.config.GrpcChannelWarmup.Sleeper;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GrpcChannelWarmupTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private GrpcChannelWarmup newWarmup(Channel channel, Clock clock, Sleeper sleeper) throws Exception {
        GrpcChannelWarmup warmup = new GrpcChannelWarmup(
                TIMEOUT, Duration.ofMillis(1), clock, sleeper, Runnable::run);
        Field field = GrpcChannelWarmup.class.getDeclaredField("channel");
        field.setAccessible(true);
        field.set(warmup, channel);
        return warmup;
    }

    private Clock fixedClock() {
        return Clock.fixed(T0, ZoneOffset.UTC);
    }

    @Test
    void readyImmediately_logsAndExits() throws Exception {
        ManagedChannel channel = mock(ManagedChannel.class);
        Sleeper sleeper = mock(Sleeper.class);
        when(channel.getState(true)).thenReturn(ConnectivityState.READY);

        newWarmup(channel, fixedClock(), sleeper).warmUp();

        verify(channel).getState(true);
        verify(channel, never()).resetConnectBackoff();
        verify(sleeper, never()).sleep(any());
    }

    @Test
    void shutdownChannel_abandons() throws Exception {
        ManagedChannel channel = mock(ManagedChannel.class);
        Sleeper sleeper = mock(Sleeper.class);
        when(channel.getState(true)).thenReturn(ConnectivityState.SHUTDOWN);

        newWarmup(channel, fixedClock(), sleeper).warmUp();

        verify(channel, never()).resetConnectBackoff();
        verify(sleeper, never()).sleep(any());
    }

    @Test
    void connectsAfterRetries() throws Exception {
        ManagedChannel channel = mock(ManagedChannel.class);
        Sleeper sleeper = mock(Sleeper.class);
        when(channel.getState(true)).thenReturn(
                ConnectivityState.TRANSIENT_FAILURE,
                ConnectivityState.CONNECTING,
                ConnectivityState.READY);
        when(sleeper.sleep(any())).thenReturn(true);

        newWarmup(channel, fixedClock(), sleeper).warmUp();

        verify(channel, times(3)).getState(true);
        verify(channel, times(2)).resetConnectBackoff();
        verify(sleeper, times(2)).sleep(Duration.ofMillis(1));
    }

    @Test
    void deadlineExceeded_abandons() throws Exception {
        ManagedChannel channel = mock(ManagedChannel.class);
        Sleeper sleeper = mock(Sleeper.class);
        when(channel.getState(true)).thenReturn(ConnectivityState.TRANSIENT_FAILURE);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(T0, T0.plusSeconds(121));

        newWarmup(channel, clock, sleeper).warmUp();

        verify(channel).getState(true);
        verify(channel, never()).resetConnectBackoff();
        verify(sleeper, never()).sleep(any());
    }

    @Test
    void interruptedDuringSleep_abandons() throws Exception {
        ManagedChannel channel = mock(ManagedChannel.class);
        Sleeper sleeper = mock(Sleeper.class);
        when(channel.getState(true)).thenReturn(ConnectivityState.TRANSIENT_FAILURE);
        when(sleeper.sleep(any())).thenReturn(false);

        newWarmup(channel, fixedClock(), sleeper).warmUp();

        verify(channel).resetConnectBackoff();
        verify(sleeper).sleep(Duration.ofMillis(1));
    }

    @Test
    void unwrapsInterceptorChannelDelegateChain() throws Exception {
        ManagedChannel delegate = mock(ManagedChannel.class);
        Sleeper sleeper = mock(Sleeper.class);
        when(delegate.getState(true)).thenReturn(ConnectivityState.READY);
        Channel wrapped = new DelegatingChannel(new DelegatingChannel(delegate));

        newWarmup(wrapped, fixedClock(), sleeper).warmUp();

        verify(delegate).getState(true);
    }

    @Test
    void unresolvableChannel_skipsWarmUp() throws Exception {
        Sleeper sleeper = mock(Sleeper.class);
        Channel opaque = new Channel() {
            @Override
            public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
                    MethodDescriptor<RequestT, ResponseT> method, CallOptions callOptions) {
                return null;
            }

            @Override
            public String authority() {
                return "opaque";
            }
        };

        newWarmup(opaque, fixedClock(), sleeper).warmUp();

        verifyNoInteractions(sleeper);
    }

    @Test
    void onApplicationReady_runsWarmUp() throws Exception {
        ManagedChannel channel = mock(ManagedChannel.class);
        Sleeper sleeper = mock(Sleeper.class);
        when(channel.getState(true)).thenReturn(ConnectivityState.READY);

        newWarmup(channel, fixedClock(), sleeper).onApplicationReady();

        verify(channel).getState(true);
    }

    @Test
    void productionConstructor_spawnsDaemonThread() throws Exception {
        ManagedChannel channel = mock(ManagedChannel.class);
        CountDownLatch latch = new CountDownLatch(1);
        when(channel.getState(true)).thenAnswer(invocation -> {
            latch.countDown();
            return ConnectivityState.READY;
        });

        GrpcChannelWarmup warmup = new GrpcChannelWarmup(TIMEOUT, Duration.ofMillis(1));
        Field field = GrpcChannelWarmup.class.getDeclaredField("channel");
        field.setAccessible(true);
        field.set(warmup, channel);

        warmup.onApplicationReady();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(mockingDetails(channel).getInvocations()).isNotEmpty();
    }

    @Test
    void realSleeper_sleepsFully() {
        assertThat(Sleeper.real().sleep(Duration.ofMillis(1))).isTrue();
    }

    @Test
    void realSleeper_interrupted() {
        Thread.currentThread().interrupt();
        assertThat(Sleeper.real().sleep(Duration.ofMillis(50))).isFalse();
        assertThat(Thread.interrupted()).isTrue();
    }

    /** Mirrors {@code io.grpc.ClientInterceptors$InterceptorChannel}: a {@code Channel} field named {@code channel}. */
    private static final class DelegatingChannel extends Channel {
        private final Channel channel;

        DelegatingChannel(Channel channel) {
            this.channel = channel;
        }

        @Override
        public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
                MethodDescriptor<RequestT, ResponseT> method, CallOptions callOptions) {
            return channel.newCall(method, callOptions);
        }

        @Override
        public String authority() {
            return channel.authority();
        }
    }
}
