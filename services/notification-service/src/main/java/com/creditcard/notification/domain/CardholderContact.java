package com.creditcard.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cardholder_contacts")
public class CardholderContact {

    @Id
    @Column(name = "card_token", length = 512)
    private String cardToken;

    @Column(name = "masked_card_number", nullable = false, length = 19)
    private String maskedCardNumber;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "transaction_notifications_enabled", nullable = false)
    private boolean transactionNotificationsEnabled = true;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        registeredAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public CardholderContact() {}

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
    public Instant getUpdatedAt() { return updatedAt; }
}
