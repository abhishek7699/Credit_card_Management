package com.creditcard.gateway.tracing;

import brave.Tracing;
import brave.grpc.GrpcTracing;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;

/**
 * Propagates the current trace into every outgoing gRPC call (gateway -> transaction-service),
 * so the transaction-service server span joins the gateway's trace. Registered globally by
 * the LogNet client starter: applies to every @GrpcClient channel in this service.
 */
@GrpcGlobalClientInterceptor
public class GrpcTracingClientInterceptor implements ClientInterceptor {

    private final ClientInterceptor delegate;

    public GrpcTracingClientInterceptor(Tracing tracing) {
        this.delegate = GrpcTracing.create(tracing).newClientInterceptor();
    }

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions callOptions, Channel next) {
        return delegate.interceptCall(method, callOptions, next);
    }
}
