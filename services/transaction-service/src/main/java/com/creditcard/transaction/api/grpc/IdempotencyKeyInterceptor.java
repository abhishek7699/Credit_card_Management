package com.creditcard.transaction.api.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

/**
 * Copies the optional "idempotency-key" gRPC metadata header (the gRPC counterpart of an
 * HTTP Idempotency-Key header) into the call Context. Absent header -> null in Context.
 * Only InitiateTransaction reads it; no other RPC is affected.
 */
@GrpcGlobalServerInterceptor
public class IdempotencyKeyInterceptor implements ServerInterceptor {

    public static final Metadata.Key<String> HEADER =
            Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER);

    public static final Context.Key<String> CONTEXT_KEY = Context.key("idempotency-key");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        Context context = Context.current().withValue(CONTEXT_KEY, headers.get(HEADER));
        return Contexts.interceptCall(context, call, headers, next);
    }
}
