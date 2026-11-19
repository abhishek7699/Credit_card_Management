package com.creditcard.transaction.tracing;

import brave.Tracing;
import brave.grpc.GrpcTracing;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

/**
 * Continues the caller's trace (gateway or booking-service) for every inbound gRPC call, and
 * makes the server span current while the handler runs, so the envelope traceId and MDC
 * line up with it. Applies to all gRPC services in this process.
 */
@GrpcGlobalServerInterceptor
public class GrpcTracingServerInterceptor implements ServerInterceptor {

    private final ServerInterceptor delegate;

    public GrpcTracingServerInterceptor(Tracing tracing) {
        this.delegate = GrpcTracing.create(tracing).newServerInterceptor();
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        return delegate.interceptCall(call, headers, next);
    }
}
