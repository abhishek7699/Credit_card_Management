package com.creditcard.notification.service;

public class CardholderContactNotFoundException extends RuntimeException {
    public CardholderContactNotFoundException(String cardToken) {
        super("No contact preferences found for card token: " + cardToken);
    }
}
