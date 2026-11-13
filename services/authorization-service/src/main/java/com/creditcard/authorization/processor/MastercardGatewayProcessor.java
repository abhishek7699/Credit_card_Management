package com.creditcard.authorization.processor;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import com.creditcard.mastercard.GatewayOutcome;
import com.creditcard.mastercard.GatewayResult;
import com.creditcard.mastercard.MastercardGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Mastercard Gateway test environment as the payment processor (spec 014 phase 2).
 *
 * <p>Our {@code transactionId} is the gateway's order ID and transaction ID for the AUTHORIZE. The card
 * is never in our database: {@code paymentToken} is sent as the gateway session. (In phase 2 that is still
 * the Vault token; phase 3 switches it to a payment session.) This class never touches
 * {@code card_accounts}. Its only local state is the authorization records and the timeout markers.
 *
 * <p>Unknown results (timeout, 5xx, duplicate identifier, unclear body) are resolved by asking the
 * gateway for the transaction's status before deciding. AUTHORIZE is never sent again for the same ID.
 *
 * <p><b>Known gap:</b> there is no card-row lock here, so a timeout release that races an in-flight
 * authorization is protected only by 013 timer rule 1 (P ≥ K+M+R+m). The marker check happens before the
 * gateway call, which closes the window only for a release that already committed.
 */
public class MastercardGatewayProcessor implements PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(MastercardGatewayProcessor.class);

    private final MastercardGatewayClient client;
    private final AuthorizationRecordRepository authorizationRecordRepository;
    private final TimeoutMarkerRepository timeoutMarkerRepository;

    public MastercardGatewayProcessor(MastercardGatewayClient client,
                                      AuthorizationRecordRepository authorizationRecordRepository,
                                      TimeoutMarkerRepository timeoutMarkerRepository) {
        this.client = client;
        this.authorizationRecordRepository = authorizationRecordRepository;
        this.timeoutMarkerRepository = timeoutMarkerRepository;
    }

    @Override
    public ProcessorDecision authorize(AuthorizeRequest request) {
        UUID transactionId = request.getTransactionId();
        if (timeoutMarkerRepository.existsById(transactionId)) {
            return ProcessorDecision.timedOutSkipped();
        }
        GatewayResult result = client.authorize(transactionId, request.getAmount(),
                request.getCurrencyCode(), request.getPaymentToken());
        ProcessorDecision decision;
        if (result.outcome() == GatewayOutcome.APPROVED) {
            decision = ProcessorDecision.approved(null);
        } else if (result.outcome() == GatewayOutcome.DECLINED) {
            decision = ProcessorDecision.declinedByProcessor(result.gatewayCode());
        } else {
            // UNKNOWN: the gateway may or may not have approved. Ask before deciding.
            decision = lookup(transactionId);
        }

        // Race fix: a release may have committed while this AUTHORIZE was in flight. If the marker is now
        // set, the transaction already timed out, so give the approved hold back at once and publish nothing.
        if (decision.getOutcome() == ProcessorOutcome.APPROVED && timeoutMarkerRepository.existsById(transactionId)) {
            log.warn("Approved txId={} after its timeout: voiding the hold at the gateway", transactionId);
            voidAtGateway(transactionId);
            return ProcessorDecision.timedOutSkipped();
        }
        return decision;
    }

    @Override
    public ProcessorDecision lookup(UUID transactionId) {
        GatewayResult result = client.retrieve(transactionId);
        if (result.outcome() == GatewayOutcome.APPROVED) {
            return ProcessorDecision.approved(null);
        }
        if (result.outcome() == GatewayOutcome.DECLINED) {
            return ProcessorDecision.declinedByProcessor(result.gatewayCode());
        }
        return ProcessorDecision.unknown();
    }

    /** Capture is phase 4 (settlement). Calling it before then is a programming error. */
    @Override
    public ProcessorDecision capture(UUID transactionId, BigDecimal amount, String currencyCode) {
        throw new UnsupportedOperationException("capture is phase 4 (spec 014)");
    }

    /**
     * Voids the authorization at the gateway, then marks our record RELEASED. With no authorization record, it
     * returns NO_AUTHORIZATION and sends nothing to the gateway. A record that was only DECLINED is a no-op.
     */
    @Override
    public ProcessorDecision releaseHold(UUID transactionId, String paymentToken) {
        List<AuthorizationRecord> records = authorizationRecordRepository.findByTransactionId(transactionId);
        if (records.isEmpty()) {
            return ProcessorDecision.noAuthorization();
        }
        List<AuthorizationRecord> held = records.stream()
                .filter(record -> record.getOutcome() == AuthorizationOutcome.AUTHORIZED)
                .toList();
        if (held.isEmpty()) {
            return ProcessorDecision.noOp();
        }

        ProcessorDecision voided = voidAtGateway(transactionId);
        if (voided.getOutcome() != ProcessorOutcome.RELEASED) {
            return voided;
        }
        for (AuthorizationRecord record : held) {
            record.setOutcome(AuthorizationOutcome.RELEASED);
            authorizationRecordRepository.save(record);
        }
        log.info("Voided authorization at the gateway for timed-out txId={}", transactionId);
        return voided;
    }

    private ProcessorDecision voidAtGateway(UUID transactionId) {
        GatewayResult result = client.voidAuthorization(transactionId);
        if (result.outcome() == GatewayOutcome.VOIDED) {
            return ProcessorDecision.released();
        }
        if (result.outcome() == GatewayOutcome.VOID_REJECTED) {
            return ProcessorDecision.releaseRejected(result.gatewayCode());
        }
        // UNKNOWN: ask whether the VOID took effect before deciding.
        GatewayResult status = client.retrieveVoid(transactionId);
        if (status.outcome() == GatewayOutcome.APPROVED) {
            return ProcessorDecision.released();
        }
        return ProcessorDecision.releaseRejected("VOID_UNKNOWN");
    }
}
