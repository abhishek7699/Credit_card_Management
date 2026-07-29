package com.creditcard.gateway.config;

import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.Channel;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfig {

    @GrpcClient("transaction-service")
    private Channel transactionServiceChannel;

    @Bean
    public TransactionServiceGrpc.TransactionServiceFutureStub transactionServiceFutureStub() {
        return TransactionServiceGrpc.newFutureStub(transactionServiceChannel);
    }
}
