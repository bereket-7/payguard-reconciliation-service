package com.payguard.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_records")
public class SettlementRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "stripe_payout_id", nullable = false)
    private String stripePayoutId;

    @Column(name = "stripe_charge_id", nullable = false, unique = true)
    private String stripeChargeId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "gross_minor", nullable = false)
    private long grossMinor;

    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;

    @Column(name = "net_minor", nullable = false)
    private long netMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "settled_at", nullable = false)
    private Instant settledAt;

    @Column(name = "raw_payload", columnDefinition = "text")
    private String rawPayload;

    protected SettlementRecord() {
    }

    public SettlementRecord(
            String stripePayoutId,
            String stripeChargeId,
            String merchantId,
            long grossMinor,
            long feeMinor,
            long netMinor,
            String currency,
            Instant settledAt,
            String rawPayload) {
        this.stripePayoutId = stripePayoutId;
        this.stripeChargeId = stripeChargeId;
        this.merchantId = merchantId;
        this.grossMinor = grossMinor;
        this.feeMinor = feeMinor;
        this.netMinor = netMinor;
        this.currency = currency;
        this.settledAt = settledAt;
        this.rawPayload = rawPayload;
    }

    @PrePersist
    void onCreate() {
        if (settledAt == null) {
            settledAt = Instant.now();
        }
    }

    public String getStripeChargeId() {
        return stripeChargeId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public long getGrossMinor() {
        return grossMinor;
    }

    public String getCurrency() {
        return currency;
    }
}
