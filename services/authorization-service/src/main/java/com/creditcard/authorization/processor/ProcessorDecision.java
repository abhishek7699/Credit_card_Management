package com.creditcard.authorization.processor;

import com.creditcard.authorization.domain.ReasonCode;

/**
 * Value returned by a {@link PaymentProcessor} operation. It never holds card data, session payloads, or
 * credentials, so {@link #toString()} is safe to log.
 */
public final class ProcessorDecision {

    private final ProcessorOutcome outcome;
    private final String reasonCode;
    private final String reference;
    private final String processorCode;

    private ProcessorDecision(ProcessorOutcome outcome, String reasonCode, String reference, String processorCode) {
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.reference = reference;
        this.processorCode = processorCode;
    }

    public static ProcessorDecision approved(String reference) {
        return new ProcessorDecision(ProcessorOutcome.APPROVED, null, reference, null);
    }

    public static ProcessorDecision declined(String reasonCode) {
        return new ProcessorDecision(ProcessorOutcome.DECLINED, reasonCode, null, null);
    }

    public static ProcessorDecision unknown() {
        return new ProcessorDecision(ProcessorOutcome.UNKNOWN, null, null, null);
    }

    public static ProcessorDecision released() {
        return new ProcessorDecision(ProcessorOutcome.RELEASED, null, null, null);
    }

    public static ProcessorDecision noAuthorization() {
        return new ProcessorDecision(ProcessorOutcome.NO_AUTHORIZATION, null, null, null);
    }

    public static ProcessorDecision noOp() {
        return new ProcessorDecision(ProcessorOutcome.NO_OP, null, null, null);
    }

    public static ProcessorDecision timedOutSkipped() {
        return new ProcessorDecision(ProcessorOutcome.TIMED_OUT_SKIPPED, null, null, null);
    }

    public static ProcessorDecision captureRejected(String reasonCode) {
        return new ProcessorDecision(ProcessorOutcome.CAPTURE_REJECTED, reasonCode, null, null);
    }

    /** Declined by the external processor. {@code processorCode} is its code, for example a gateway reason. */
    public static ProcessorDecision declinedByProcessor(String processorCode) {
        return new ProcessorDecision(ProcessorOutcome.DECLINED, ReasonCode.PROCESSOR_DECLINED.name(), null, processorCode);
    }

    /** The processor refused to void the hold. The hold stays as it was. */
    public static ProcessorDecision releaseRejected(String reasonCode) {
        return new ProcessorDecision(ProcessorOutcome.RELEASE_REJECTED, reasonCode, null, null);
    }

    public ProcessorOutcome getOutcome() {
        return outcome;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getProcessorCode() {
        return processorCode;
    }

    public String getReference() {
        return reference;
    }

    @Override
    public String toString() {
        return "ProcessorDecision{outcome=" + outcome + ", reasonCode=" + reasonCode + "}";
    }
}
