package com.payguard.reconciliation.service;

import com.payguard.reconciliation.domain.Discrepancy;
import com.payguard.reconciliation.domain.ReconciliationRun;
import com.payguard.reconciliation.repository.DiscrepancyRepository;
import com.payguard.reconciliation.repository.ReconciliationRunRepository;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    private final ReconciliationRunRepository runs;
    private final DiscrepancyRepository discrepancies;

    public ReportService(ReconciliationRunRepository runs, DiscrepancyRepository discrepancies) {
        this.runs = runs;
        this.discrepancies = discrepancies;
    }

    public List<ReconciliationRun> listRuns() {
        return runs.findAll();
    }

    public ReconciliationRun getRun(String runId) {
        return runs.findById(runId).orElseThrow(() -> new NoSuchElementException("run not found"));
    }

    public List<Discrepancy> listDiscrepancies(String runId, String merchantId) {
        return discrepancies.findByRunIdAndMerchantId(runId, merchantId);
    }

    @Transactional
    public Discrepancy resolveDiscrepancy(java.util.UUID id, String note) {
        Discrepancy discrepancy = discrepancies.findById(id).orElseThrow(() -> new NoSuchElementException("discrepancy not found"));
        discrepancy.resolve(note);
        return discrepancies.save(discrepancy);
    }
}
