package com.creditcard.transaction.tracing;

import brave.Tracing;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GrpcTracingServerInterceptorTest {

    private static final MethodDescriptor.Marshaller<String> MARSHALLER = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes());
        }

        @Override
        public String parse(InputStream stream) {
            return "";
        }
    };

    @Test
    void interceptCallDelegatesToTheTracingInterceptorAndStartsTheHandler() {
        Tracing tracing = Tracing.newBuilder().build();
        GrpcTracingServerInterceptor interceptor = new GrpcTracingServerInterceptor(tracing);

        MethodDescriptor<String, String> method = MethodDescriptor.<String, String>newBuilder(MARSHALLER, MARSHALLER)
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("transaction.TransactionService/InitiateTransaction")
                .build();
        ServerCall<String, String> call = mock(ServerCall.class);
        doReturn(method).when(call).getMethodDescriptor();
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
        ServerCall.Listener<String> listener = mock(ServerCall.Listener.class);
        doReturn(listener).when(next).startCall(any(), any());

        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        assertThat(result).isNotNull();
        verify(next).startCall(any(), any());
        tracing.close();
    }
}
