package com.payguard.reconciliation.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.payguard.reconciliation.domain.DiscrepancyType;
import com.payguard.reconciliation.domain.ExpectedPayment;
import com.payguard.reconciliation.domain.SettlementRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private final MatchingEngine engine = new MatchingEngine(1);

    @Test
    void matchesSettlementByStripeChargeId() {
        ExpectedPayment expected = payment("txn_1", "mer_1", 10_000, "ch_1");
        SettlementRecord settlement = settlement("ch_1", "mer_1", 10_000);

        MatchingEngine.MatchResult result = engine.match("run_1", List.of(expected), List.of(settlement));

        assertThat(result.matchedTransactionIds()).containsExactly("txn_1");
        assertThat(result.discrepancies()).isEmpty();
    }

    @Test
    void reportsAmountMismatchWithDirection() {
        ExpectedPayment expected = payment("txn_2", "mer_1", 10_000, "ch_2");
        SettlementRecord settlement = settlement("ch_2", "mer_1", 9_500);

        MatchingEngine.MatchResult result = engine.match("run_2", List.of(expected), List.of(settlement));

        assertThat(result.matchedTransactionIds()).isEmpty();
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().get(0).getDiscrepancyType()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(result.discrepancies().get(0).getDetails()).contains("under-settled");
    }

    @Test
    void flagsSettlementMissingFromLedger() {
        SettlementRecord settlement = settlement("ch_missing", "mer_1", 4_000);

        MatchingEngine.MatchResult result = engine.match("run_3", List.of(), List.of(settlement));

        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().get(0).getDiscrepancyType()).isEqualTo(DiscrepancyType.MISSING_IN_LEDGER);
    }

    @Test
    void doesNotMatchSameLedgerRowToTwoSettlementsByAmountFallback() {
        ExpectedPayment expected = payment("txn_3", "mer_1", 5_000, null);
        SettlementRecord first = settlement("ch_a", "mer_1", 5_000);
        SettlementRecord second = settlement("ch_b", "mer_1", 5_000);

        MatchingEngine.MatchResult result =
                engine.match("run_4", List.of(expected), List.of(first, second));

        assertThat(result.matchedTransactionIds()).containsExactly("txn_3");
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().get(0).getDiscrepancyType()).isEqualTo(DiscrepancyType.MISSING_IN_LEDGER);
    }

    @Test
    void reportsMissingSettlementForLedgerEntryWithChargeId() {
        ExpectedPayment expected = payment("txn_5", "mer_1", 3_000, "ch_orphan");

        MatchingEngine.MatchResult result = engine.match("run_5", List.of(expected), List.of());

        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().get(0).getDiscrepancyType())
                .isEqualTo(DiscrepancyType.MISSING_IN_SETTLEMENT);
    }

    private static ExpectedPayment payment(String txn, String merchant, long amount, String chargeId) {
        ExpectedPayment payment =
                new ExpectedPayment(txn, merchant, amount, "USD", "COMPLETED", Instant.parse("2026-01-01T00:00:00Z"));
        if (chargeId != null) {
            payment.complete(chargeId);
        }
        return payment;
    }

    private static SettlementRecord settlement(String chargeId, String merchant, long gross) {
        return new SettlementRecord(
                "po_1",
                chargeId,
                merchant,
                gross,
                100,
                gross - 100,
                "USD",
                Instant.parse("2026-01-02T00:00:00Z"),
                "{}");
    }
}
