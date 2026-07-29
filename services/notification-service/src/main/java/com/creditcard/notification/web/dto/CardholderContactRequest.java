package com.creditcard.notification.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class CardholderContactRequest {

    @NotBlank(message = "cardToken is required")
    private String cardToken;

    @NotBlank(message = "maskedCardNumber is required")
    private String maskedCardNumber;

    @Email(message = "email must be a valid RFC 5322 address")
    private String email;

    @Pattern(regexp = "\\+\\d{7,15}", message = "phoneNumber must start with + followed by 7-15 digits")
    private String phoneNumber;

    private Boolean transactionNotificationsEnabled;

    @AssertTrue(message = "At least one of email or phoneNumber must be provided")
    public boolean isAtLeastOneContactPresent() {
        return (email != null && !email.isBlank()) || (phoneNumber != null && !phoneNumber.isBlank());
    }

    @AssertTrue(message = "maskedCardNumber must not be an unmasked card number")
    public boolean isMaskedCardNumberSafe() {
        if (maskedCardNumber == null) return true;
        return !maskedCardNumber.replaceAll("[^\\d]", "").matches("\\d{13,19}");
    }

    public String getCardToken() { return cardToken; }
    public void setCardToken(String cardToken) { this.cardToken = cardToken; }

    public String getMaskedCardNumber() { return maskedCardNumber; }
    public void setMaskedCardNumber(String maskedCardNumber) { this.maskedCardNumber = maskedCardNumber; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public Boolean getTransactionNotificationsEnabled() { return transactionNotificationsEnabled; }
    public void setTransactionNotificationsEnabled(Boolean transactionNotificationsEnabled) {
        this.transactionNotificationsEnabled = transactionNotificationsEnabled;
    }
}
