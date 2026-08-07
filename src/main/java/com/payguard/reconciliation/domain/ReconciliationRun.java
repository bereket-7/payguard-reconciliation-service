package com.payguard.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    @Column(name = "settlement_date", nullable = false, updatable = false)
    private LocalDate settlementDate;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "expected_count")
    private int expectedCount;

    @Column(name = "settled_count")
    private int settledCount;

    @Column(name = "matched_count")
    private int matchedCount;

    @Column(name = "discrepancy_count")
    private int discrepancyCount;

    protected ReconciliationRun() {
    }

    public ReconciliationRun(String runId, LocalDate settlementDate) {
        this.runId = runId;
        this.settlementDate = settlementDate;
        this.status = RunStatus.RUNNING;
    }

    @PrePersist
    void onCreate() {
        startedAt = Instant.now();
    }

    public String getRunId() {
        return runId;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public RunStatus getStatus() {
        return status;
    }

    public int getMatchedCount() {
        return matchedCount;
    }

    public int getDiscrepancyCount() {
        return discrepancyCount;
    }

    public void complete(int expected, int settled, int matched, int discrepancies) {
        expectedCount = expected;
        settledCount = settled;
        matchedCount = matched;
        discrepancyCount = discrepancies;
        status = RunStatus.COMPLETED;
        completedAt = Instant.now();
    }
}
