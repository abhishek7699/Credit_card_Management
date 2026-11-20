package com.creditcard.mastercard.developers;

/**
 * Account Validation could not give an answer (network failure, timeout, 401, 5xx). This is NOT a
 * "card declined": callers must fail closed and let the guest retry. The message never carries the
 * request or response body, so it is safe to log.
 */
public class AccountValidationUnavailableException extends RuntimeException {
    public AccountValidationUnavailableException(String message) {
        super(message);
    }

    public AccountValidationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
