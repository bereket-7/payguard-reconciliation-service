package com.payguard.reconciliation.controller;

import com.payguard.reconciliation.domain.Discrepancy;
import com.payguard.reconciliation.domain.ReconciliationRun;
import com.payguard.reconciliation.service.ReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/runs")
public class RunController {

    private final ReportService reports;

    public RunController(ReportService reports) {
        this.reports = reports;
    }

    public record ResolveRequest(@Size(max = 1000) String note) {
    }

    /**
     * Run metadata spans every merchant on the platform, so this is deliberately platform-admin
     * only — it was previously open to any authenticated merchant, leaking cross-merchant
     * settlement volumes and discrepancy counts.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_platform:admin')")
    public List<ReconciliationRun> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return reports.listRuns(page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_platform:admin')")
    public ReconciliationRun get(@PathVariable String id) {
        return reports.getRun(id);
    }

    /** Merchant-scoped: a merchant sees only its own discrepancies within a run. */
    @GetMapping("/{id}/discrepancies")
    public List<Discrepancy> discrepancies(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return reports.listDiscrepancies(id, merchant(jwt));
    }

    @PostMapping("/discrepancies/{id}/resolve")
    public Discrepancy resolve(
            @PathVariable UUID id, @Valid @RequestBody ResolveRequest request, @AuthenticationPrincipal Jwt jwt) {
        String note = request.note() == null || request.note().isBlank() ? "resolved" : request.note();
        return reports.resolveDiscrepancy(id, merchant(jwt), note);
    }

    private String merchant(Jwt jwt) {
        if (jwt == null) {
            throw new AccessDeniedException("bearer token required");
        }
        String merchantId = jwt.getClaimAsString("merchant_id");
        if (merchantId == null) {
            throw new AccessDeniedException("merchant_id claim is required");
        }
        return merchantId;
    }
}
