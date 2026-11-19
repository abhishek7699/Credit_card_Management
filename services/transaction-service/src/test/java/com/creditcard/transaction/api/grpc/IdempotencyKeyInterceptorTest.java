package com.creditcard.transaction.api.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyKeyInterceptorTest {

    @Mock
    private ServerCall<Object, Object> call;

    @Mock
    private ServerCallHandler<Object, Object> next;

    @Mock
    private ServerCall.Listener<Object> listener;

    private final IdempotencyKeyInterceptor interceptor = new IdempotencyKeyInterceptor();

    /** Records the idempotency key visible to the downstream handler, then returns a listener. */
    private AtomicReference<String> stubHandlerCapturingKey() {
        AtomicReference<String> seen = new AtomicReference<>("<not called>");
        when(next.startCall(any(), any())).thenAnswer(inv -> {
            seen.set(IdempotencyKeyInterceptor.CONTEXT_KEY.get());
            return listener;
        });
        return seen;
    }

    @Test
    void exposesHeaderValueToHandlerViaContext() {
        AtomicReference<String> seen = stubHandlerCapturingKey();
        Metadata headers = new Metadata();
        headers.put(IdempotencyKeyInterceptor.HEADER, "order-42");

        interceptor.interceptCall(call, headers, next);

        assertThat(seen.get()).isEqualTo("order-42");
    }

    @Test
    void exposesNullWhenHeaderAbsent() {
        AtomicReference<String> seen = stubHandlerCapturingKey();

        interceptor.interceptCall(call, new Metadata(), next);

        assertThat(seen.get()).isNull();
    }
}
