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
@Table(name = "expected_payments")
public class ExpectedPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true, updatable = false)
    private String transactionId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "stripe_charge_id")
    private String stripeChargeId;

    @Column(nullable = false)
    private String status;

    @Column(name = "expected_at", nullable = false)
    private Instant expectedAt;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "run_id")
    private String runId;

    protected ExpectedPayment() {
    }

    public ExpectedPayment(
            String transactionId,
            String merchantId,
            long amountMinor,
            String currency,
            String status,
            Instant expectedAt) {
        this.transactionId = transactionId;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.expectedAt = expectedAt;
    }

    @PrePersist
    void onCreate() {
        if (expectedAt == null) {
            expectedAt = Instant.now();
        }
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStripeChargeId() {
        return stripeChargeId;
    }

    public String getStatus() {
        return status;
    }

    public void complete(String stripeChargeId) {
        this.stripeChargeId = stripeChargeId;
        this.status = "COMPLETED";
    }

    public void markReconciled(String runId) {
        this.runId = runId;
        this.reconciledAt = Instant.now();
    }

    public void applyRefund() {
        this.status = "REFUNDED";
    }
}
