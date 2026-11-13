package com.creditcard.authorization.processor;

/**
 * Value returned by a {@link PaymentProcessor} operation. It never holds card data, session payloads, or
 * credentials, so {@link #toString()} is safe to log.
 */
public final class ProcessorDecision {

    private final ProcessorOutcome outcome;
    private final String reasonCode;
    private final String reference;

    private ProcessorDecision(ProcessorOutcome outcome, String reasonCode, String reference) {
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.reference = reference;
    }

    public static ProcessorDecision approved(String reference) {
        return new ProcessorDecision(ProcessorOutcome.APPROVED, null, reference);
    }

    public static ProcessorDecision declined(String reasonCode) {
        return new ProcessorDecision(ProcessorOutcome.DECLINED, reasonCode, null);
    }

    public static ProcessorDecision unknown() {
        return new ProcessorDecision(ProcessorOutcome.UNKNOWN, null, null);
    }

    public static ProcessorDecision released() {
        return new ProcessorDecision(ProcessorOutcome.RELEASED, null, null);
    }

    public static ProcessorDecision noAuthorization() {
        return new ProcessorDecision(ProcessorOutcome.NO_AUTHORIZATION, null, null);
    }

    public static ProcessorDecision noOp() {
        return new ProcessorDecision(ProcessorOutcome.NO_OP, null, null);
    }

    public static ProcessorDecision timedOutSkipped() {
        return new ProcessorDecision(ProcessorOutcome.TIMED_OUT_SKIPPED, null, null);
    }

    public static ProcessorDecision captureRejected(String reasonCode) {
        return new ProcessorDecision(ProcessorOutcome.CAPTURE_REJECTED, reasonCode, null);
    }

    public ProcessorOutcome getOutcome() {
        return outcome;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReference() {
        return reference;
    }

    @Override
    public String toString() {
        return "ProcessorDecision{outcome=" + outcome + ", reasonCode=" + reasonCode + "}";
    }
}
