package com.payguard.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "discrepancies")
public class Discrepancy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, updatable = false)
    private DiscrepancyType discrepancyType;

    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "stripe_charge_id")
    private String stripeChargeId;

    @Column(nullable = false, columnDefinition = "text")
    private String details;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Discrepancy() {
    }

    public Discrepancy(
            String runId,
            String merchantId,
            DiscrepancyType discrepancyType,
            String transactionId,
            String stripeChargeId,
            String details) {
        this.runId = runId;
        this.merchantId = merchantId;
        this.discrepancyType = discrepancyType;
        this.transactionId = transactionId;
        this.stripeChargeId = stripeChargeId;
        this.details = details;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public String getRunId() {
        return runId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public DiscrepancyType getDiscrepancyType() {
        return discrepancyType;
    }

    public String getDetails() {
        return details;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void resolve(String note) {
        resolutionNote = note;
    }
}
