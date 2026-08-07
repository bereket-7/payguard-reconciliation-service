package com.payguard.reconciliation.controller;

import com.payguard.reconciliation.domain.Discrepancy;
import com.payguard.reconciliation.domain.ReconciliationRun;
import com.payguard.reconciliation.service.ReportService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/runs")
public class RunController {

    private final ReportService reports;

    public RunController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    public List<ReconciliationRun> list() {
        return reports.listRuns();
    }

    @GetMapping("/{id}")
    public ReconciliationRun get(@PathVariable String id) {
        return reports.getRun(id);
    }

    @GetMapping("/{id}/discrepancies")
    public List<Discrepancy> discrepancies(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return reports.listDiscrepancies(id, merchant(jwt));
    }

    @PostMapping("/discrepancies/{id}/resolve")
    public Discrepancy resolve(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return reports.resolveDiscrepancy(id, body.getOrDefault("note", "resolved"));
    }

    private String merchant(Jwt jwt) {
        String merchantId = jwt.getClaimAsString("merchant_id");
        if (merchantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("merchant_id claim is required");
        }
        return merchantId;
    }
}
