package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.CardDetails;
import com.creditcard.booking.exception.CardNotVerifiedException;
import com.creditcard.booking.exception.CardValidationUnavailableException;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import com.creditcard.transaction.grpc.v1.ValidateCardRequest;
import com.creditcard.transaction.grpc.v1.ValidateCardResponse;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Asks transaction-service to check a guest's card (no charge). The card number and CVC are passed straight
 * through for the one call and are never stored or logged here: only a status code is ever logged.
 * Fails closed: if the check cannot complete, the booking is not confirmed.
 */
@Service
public class CardValidationService {

    private static final Logger log = LoggerFactory.getLogger(CardValidationService.class);

    private final TransactionServiceGrpc.TransactionServiceBlockingStub stub;
    private final long deadlineSeconds;

    public CardValidationService(TransactionServiceGrpc.TransactionServiceBlockingStub stub,
                                 @Value("${booking.card-validation.deadline-seconds:8}") long deadlineSeconds) {
        this.stub = stub;
        this.deadlineSeconds = deadlineSeconds;
    }

    public CardValidationResult validate(CardDetails card) {
        ValidateCardResponse response;
        try {
            response = stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).validateCard(
                    ValidateCardRequest.newBuilder()
                            .setCardNumber(card.cardNumber())
                            .setCardExpiry(card.expiry())
                            .setCvv(card.cvv())
                            .setCardholderName(card.cardholderName())
                            .build());
        } catch (StatusRuntimeException e) {
            log.warn("Guest card validation call failed: {}", e.getStatus().getCode());
            if (e.getStatus().getCode() == io.grpc.Status.Code.INVALID_ARGUMENT) {
                throw new CardNotVerifiedException();
            }
            throw new CardValidationUnavailableException();
        }
        if (!"MATCHED".equals(response.getStatus())) {
            log.info("Guest card validation not matched, referenceId={}", response.getReferenceId());
            throw new CardNotVerifiedException();
        }
        return new CardValidationResult(response.getStatus(), response.getReferenceId(), response.getLast4());
    }
}
