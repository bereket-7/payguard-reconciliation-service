package com.payguard.reconciliation.service;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Raised when another replica already holds the run slot for a settlement date.
 *
 * <p>Mapped to 409 rather than the previous {@code IllegalStateException} (which surfaced as a 500)
 * so an operator retriggering a run can tell "already running" apart from "the run crashed".
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ReconciliationInProgressException extends RuntimeException {

    private final LocalDate settlementDate;

    public ReconciliationInProgressException(LocalDate settlementDate) {
        super("reconciliation for " + settlementDate + " is already running");
        this.settlementDate = settlementDate;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }
}
