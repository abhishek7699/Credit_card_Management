package com.creditcard.authorization.domain;

public enum ReasonCode {
    INVALID_CARD,
    CARD_NOT_ACTIVE,
    INSUFFICIENT_CREDIT,
    /** Declined by an external processor. Its own code is kept in processor_reason_code. */
    PROCESSOR_DECLINED,
    INVALID_EVENT_PAYLOAD,
    INTERNAL_ERROR
}
