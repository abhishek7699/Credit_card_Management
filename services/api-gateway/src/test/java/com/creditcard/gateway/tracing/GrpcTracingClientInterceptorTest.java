package com.creditcard.gateway.tracing;

import brave.Tracing;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GrpcTracingClientInterceptorTest {

    @Test
    void interceptCallWrapsTheCallOnTheNextChannel() {
        Tracing tracing = Tracing.newBuilder().build();
        GrpcTracingClientInterceptor interceptor = new GrpcTracingClientInterceptor(tracing);

        MethodDescriptor<String, String> method = mock(MethodDescriptor.class);
        Channel next = mock(Channel.class);
        ClientCall<String, String> downstream = mock(ClientCall.class);
        doReturn(downstream).when(next).newCall(any(), any());

        ClientCall<String, String> call = interceptor.interceptCall(method, CallOptions.DEFAULT, next);

        assertThat(call).isNotNull();
        verify(next).newCall(method, CallOptions.DEFAULT);
        tracing.close();
    }
}
