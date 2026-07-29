package com.creditcard.frauddetection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "fraud_rule_configs")
public class FraudRuleConfig {

    @Id
    @Column(name = "rule_type", length = 50, nullable = false)
    private String ruleType;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "parameters", nullable = false)
    private String parameters;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
