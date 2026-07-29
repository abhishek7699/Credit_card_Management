package com.creditcard.notification.web.dto;

import java.time.Instant;

public class CardholderContactResponse {

    private String cardToken;
    private String maskedCardNumber;
    private String email;
    private String phoneNumber;
    private boolean transactionNotificationsEnabled;
    private Instant registeredAt;
    private Instant updatedAt;

    public String getCardToken() { return cardToken; }
    public void setCardToken(String cardToken) { this.cardToken = cardToken; }

    public String getMaskedCardNumber() { return maskedCardNumber; }
    public void setMaskedCardNumber(String maskedCardNumber) { this.maskedCardNumber = maskedCardNumber; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public boolean isTransactionNotificationsEnabled() { return transactionNotificationsEnabled; }
    public void setTransactionNotificationsEnabled(boolean transactionNotificationsEnabled) {
        this.transactionNotificationsEnabled = transactionNotificationsEnabled;
    }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
