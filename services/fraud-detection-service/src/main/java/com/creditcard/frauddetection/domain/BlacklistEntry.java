package com.creditcard.frauddetection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "blacklist_entries")
public class BlacklistEntry {

    @Id
    @Column(name = "card_token", length = 512, nullable = false)
    private String cardToken;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    public String getCardToken() { return cardToken; }
    public void setCardToken(String cardToken) { this.cardToken = cardToken; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }
}
