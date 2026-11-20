package com.creditcard.booking.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import java.util.function.Supplier;

/**
 * Sends the Idempotency-Key metadata that transaction-service reads on InitiateTransaction. The key
 * is only set inside {@link #withKey}, so calls made outside it carry no key.
 */
public final class IdempotencyKeyClientInterceptor implements ClientInterceptor {

    static final Metadata.Key<String> HEADER = Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER);

    private static final Context.Key<String> KEY = Context.key("idempotency-key");

    /** Runs {@code call} on this thread with {@code key} attached to every gRPC call it makes. */
    public static <T> T withKey(String key, Supplier<T> call) {
        Context attached = Context.current().withValue(KEY, key);
        Context previous = attached.attach();
        try {
            return call.get();
        } finally {
            attached.detach(previous);
        }
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String key = KEY.get();
                if (key != null) {
                    headers.put(HEADER, key);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
