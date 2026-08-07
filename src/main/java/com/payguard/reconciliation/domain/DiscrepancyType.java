package com.payguard.reconciliation.domain;

public enum DiscrepancyType {
    MISSING_IN_SETTLEMENT,
    MISSING_IN_LEDGER,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    DUPLICATE_SETTLEMENT,
    LATE_SETTLEMENT,
    REFUND_ADJUSTMENT
}
