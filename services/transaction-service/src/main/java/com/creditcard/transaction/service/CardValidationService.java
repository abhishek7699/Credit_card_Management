package com.creditcard.transaction.service;

import com.creditcard.mastercard.developers.AccountValidationClient;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Checks a card with Mastercard Account Validation (no charge, nothing persisted). The card number and CVC
 * exist only as method arguments and inside the outgoing request: this class never stores or logs them.
 *
 * <p>The switch {@code card-validation.enabled} decides whether the real call is made. When it is off (the
 * default, so CI and the e2e need no Mastercard credentials) the card is only shape-checked and always passes.
 */
@Service
public class CardValidationService {

    private static final Logger log = LoggerFactory.getLogger(CardValidationService.class);
    private static final Pattern PAN = Pattern.compile("\\d{13,19}");
    private static final Pattern CVC = Pattern.compile("\\d{3,4}");
    private static final String OFF_PREFIX = "OFF_";

    public enum Status { MATCHED, NOT_MATCHED }

    /** The only card-related data that leaves this service: the verdict, our reference, and the last 4 digits. */
    public record Outcome(Status status, String referenceId, String last4) {}

    private final AccountValidationClient client;
    private final String referencePrefix;

    public CardValidationService(ObjectProvider<AccountValidationClient> client,
                                 @Value("${card-validation.reference-prefix:}") String referencePrefix) {
        this.client = client.getIfAvailable();
        this.referencePrefix = referencePrefix;
    }

    /**
     * @throws IllegalArgumentException (fixed message, never the value) for malformed card data
     * @throws com.creditcard.mastercard.developers.AccountValidationUnavailableException when Mastercard cannot answer
     */
    public Outcome validate(String cardNumber, String expiryMmYy, String cvc, String cardholderName) {
        if (cardNumber == null || !PAN.matcher(cardNumber).matches()) {
            throw new IllegalArgumentException("card_number must be 13-19 digits");
        }
        if (cvc == null || !CVC.matcher(cvc).matches()) {
            throw new IllegalArgumentException("cvv must be 3 or 4 digits");
        }
        String last4 = cardNumber.substring(cardNumber.length() - 4);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        if (client == null) {
            // Switch off: still reject a malformed expiry so dev and CI behave the same on bad input.
            AccountValidationClient.Request.fromCard(OFF_PREFIX + suffix, cardNumber, expiryMmYy, cvc, cardholderName);
            return new Outcome(Status.MATCHED, OFF_PREFIX + suffix, last4);
        }

        String referenceId = referencePrefix + suffix;
        AccountValidationClient.Validation result = client.validate(
                AccountValidationClient.Request.fromCard(referenceId, cardNumber, expiryMmYy, cvc, cardholderName),
                LocalDateTime.now());
        Status status = result.approved() ? Status.MATCHED : Status.NOT_MATCHED;
        log.info("Card validation {} referenceId={}", status, referenceId);
        return new Outcome(status, referenceId, last4);
    }
}
