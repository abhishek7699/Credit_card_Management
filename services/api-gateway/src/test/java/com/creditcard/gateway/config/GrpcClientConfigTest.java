package com.creditcard.gateway.config;

import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.Channel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GrpcClientConfigTest {

    private TransactionServiceGrpc.TransactionServiceFutureStub makeStub() throws Exception {
        GrpcClientConfig config = new GrpcClientConfig();
        Field channelField = GrpcClientConfig.class.getDeclaredField("transactionServiceChannel");
        channelField.setAccessible(true);
        channelField.set(config, mock(Channel.class));
        return config.transactionServiceFutureStub();
    }

    @Test
    void futureStubBeanIsNotNull() throws Exception {
        assertThat(makeStub()).isNotNull();
    }

    @Test
    void futureStubIsCorrectType() throws Exception {
        assertThat(makeStub()).isInstanceOf(TransactionServiceGrpc.TransactionServiceFutureStub.class);
    }
}
