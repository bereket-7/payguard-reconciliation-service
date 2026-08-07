package com.payguard.reconciliation.repository;

import com.payguard.reconciliation.domain.ReconciliationRun;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, String> {

    List<ReconciliationRun> findBySettlementDateOrderByStartedAtDesc(LocalDate settlementDate);
}
