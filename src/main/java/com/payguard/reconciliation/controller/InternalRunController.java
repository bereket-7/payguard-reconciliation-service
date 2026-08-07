package com.payguard.reconciliation.controller;

import com.payguard.reconciliation.domain.ReconciliationRun;
import com.payguard.reconciliation.service.ReconciliationRunService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/runs")
public class InternalRunController {

    private final ReconciliationRunService runService;

    public InternalRunController(ReconciliationRunService runService) {
        this.runService = runService;
    }

    @PostMapping
    public Map<String, Object> trigger(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ReconciliationRun run = runService.runForDate(date);
        return Map.of("run_id", run.getRunId(), "status", run.getStatus(), "discrepancy_count", run.getDiscrepancyCount());
    }
}
