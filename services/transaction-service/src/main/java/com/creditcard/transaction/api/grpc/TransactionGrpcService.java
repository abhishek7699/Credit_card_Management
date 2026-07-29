package com.creditcard.transaction.api.grpc;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.grpc.v1.GetTransactionRequest;
import com.creditcard.transaction.grpc.v1.GetTransactionResponse;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import com.creditcard.transaction.service.TransactionService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@GrpcService
public class TransactionGrpcService extends TransactionServiceGrpc.TransactionServiceImplBase {

    private final TransactionService transactionService;

    public TransactionGrpcService(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Override
    public void initiateTransaction(
            InitiateTransactionRequest request,
            StreamObserver<InitiateTransactionResponse> responseObserver) {

        if (request.getCardNumber().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("card_number is required")
                    .asRuntimeException());
            return;
        }
        if (request.getCardholderName().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("cardholder_name is required")
                    .asRuntimeException());
            return;
        }
        if (request.getAmount().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("amount is required")
                    .asRuntimeException());
            return;
        }
        if (request.getCurrencyCode().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("currency_code is required")
                    .asRuntimeException());
            return;
        }

        try {
            BigDecimal amount = new BigDecimal(request.getAmount());
            UUID transactionId = transactionService.initiateTransaction(
                    request.getCardNumber(),
                    request.getCardholderName(),
                    amount,
                    request.getCurrencyCode(),
                    request.getMerchantId()
            );

            responseObserver.onNext(InitiateTransactionResponse.newBuilder()
                    .setTransactionId(transactionId.toString())
                    .setStatus("PENDING")
                    .build());
            responseObserver.onCompleted();

        } catch (NumberFormatException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("amount must be a valid decimal number")
                    .asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Transaction initiation failed")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void getTransaction(
            GetTransactionRequest request,
            StreamObserver<GetTransactionResponse> responseObserver) {

        if (request.getTransactionId().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("transaction_id is required")
                    .asRuntimeException());
            return;
        }

        try {
            Optional<Transaction> found = transactionService.getTransaction(request.getTransactionId());
            if (found.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Transaction not found: " + request.getTransactionId())
                        .asRuntimeException());
                return;
            }

            Transaction tx = found.get();
            responseObserver.onNext(GetTransactionResponse.newBuilder()
                    .setTransactionId(tx.getId().toString())
                    .setMaskedCardNumber(tx.getMaskedCardNumber())
                    .setCardholderName(tx.getCardholderName())
                    .setAmount(tx.getAmount().toPlainString())
                    .setCurrencyCode(tx.getCurrencyCode())
                    .setStatus(tx.getStatus().name())
                    .setCreatedAt(tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : "")
                    .setUpdatedAt(tx.getUpdatedAt() != null ? tx.getUpdatedAt().toString() : "")
                    .setMerchantId(tx.getMerchantId() != null ? tx.getMerchantId() : "")
                    .build());
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid transaction_id format")
                    .asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to retrieve transaction")
                    .withCause(e)
                    .asRuntimeException());
        }
    }
}
