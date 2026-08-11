package com.payguard.reconciliation.service;

import com.payguard.reconciliation.domain.Discrepancy;
import com.payguard.reconciliation.domain.ReconciliationRun;
import com.payguard.reconciliation.repository.DiscrepancyRepository;
import com.payguard.reconciliation.repository.ReconciliationRunRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    /** Caps an unbounded {@code findAll()} that would have serialised the entire run history. */
    private static final int MAX_PAGE_SIZE = 200;

    private final ReconciliationRunRepository runs;
    private final DiscrepancyRepository discrepancies;

    public ReportService(ReconciliationRunRepository runs, DiscrepancyRepository discrepancies) {
        this.runs = runs;
        this.discrepancies = discrepancies;
    }

    public List<ReconciliationRun> listRuns(int page, int size) {
        return runs.findAll(PageRequest.of(
                        Math.max(page, 0),
                        Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "startedAt")))
                .getContent();
    }

    public ReconciliationRun getRun(String runId) {
        return runs.findById(runId).orElseThrow(() -> new NoSuchElementException("run not found"));
    }

    public List<Discrepancy> listDiscrepancies(String runId, String merchantId) {
        return discrepancies.findByRunIdAndMerchantId(runId, merchantId);
    }

    /**
     * Resolves a discrepancy on behalf of one merchant.
     *
     * <p>The caller's merchant id is now required and checked. Previously the endpoint took only the
     * discrepancy id, so any authenticated merchant could resolve — and thereby close out —
     * a discrepancy belonging to any other merchant on the platform.
     */
    @Transactional
    public Discrepancy resolveDiscrepancy(UUID id, String merchantId, String note) {
        Discrepancy discrepancy =
                discrepancies.findById(id).orElseThrow(() -> new NoSuchElementException("discrepancy not found"));

        if (!discrepancy.getMerchantId().equals(merchantId)) {
            // Deliberately the same shape of failure a missing discrepancy would produce upstream,
            // so probing ids does not confirm which ones exist.
            throw new AccessDeniedException("discrepancy does not belong to this merchant");
        }

        discrepancy.resolve(note);
        return discrepancies.save(discrepancy);
    }
}
