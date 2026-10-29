package com.creditcard.booking.grpc;

import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.Channel;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Blocking (not future) stub, unlike api-gateway's GrpcClientConfig: booking-service is
 * a synchronous Spring MVC service with no reactive chain to compose a future against
 * (research.md §2). The per-call deadline (5s) is applied at each call site via
 * {@code stub.withDeadlineAfter(...)}, not baked into this bean.
 */
@Configuration
public class TransactionServiceClientConfig {

    @GrpcClient("transaction-service")
    private Channel transactionServiceChannel;

    @Bean
    public TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceBlockingStub() {
        return TransactionServiceGrpc.newBlockingStub(transactionServiceChannel);
    }
}
