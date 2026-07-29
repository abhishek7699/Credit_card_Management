package com.creditcard.transaction.service;

public final class TokenResult {

    private final String token;
    private final String maskedCardNumber;

    public TokenResult(String token, String maskedCardNumber) {
        this.token = token;
        this.maskedCardNumber = maskedCardNumber;
    }

    public String getToken() { return token; }
    public String getMaskedCardNumber() { return maskedCardNumber; }
}
