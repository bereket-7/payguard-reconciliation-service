package com.payguard.reconciliation.repository;

import com.payguard.reconciliation.domain.SettlementRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, UUID> {

    Optional<SettlementRecord> findByStripeChargeId(String stripeChargeId);

    List<SettlementRecord> findByStripePayoutId(String stripePayoutId);
}
