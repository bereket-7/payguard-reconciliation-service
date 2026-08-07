package com.payguard.reconciliation.repository;

import com.payguard.reconciliation.domain.Discrepancy;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscrepancyRepository extends JpaRepository<Discrepancy, UUID> {

    List<Discrepancy> findByRunIdAndMerchantId(String runId, String merchantId);
}
