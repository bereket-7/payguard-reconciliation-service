package com.payguard.reconciliation.matching;

import com.payguard.reconciliation.domain.Discrepancy;
import com.payguard.reconciliation.domain.DiscrepancyType;
import com.payguard.reconciliation.domain.ExpectedPayment;
import com.payguard.reconciliation.domain.SettlementRecord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Matches ledger entries against Stripe settlements.
 *
 * <p>Rewritten to consume matches. The previous implementation had three defects that all produced
 * wrong money numbers:
 *
 * <ul>
 *   <li>The amount-fallback used {@code expected.stream()...findFirst()} without removing the
 *       candidate, so N settlements of the same amount for one merchant all matched the <em>same</em>
 *       ledger row. One real payment was reported as reconciled N times and N−1 genuinely missing
 *       ledger entries were silently swallowed.
 *   <li>That fallback was also a linear scan inside the settlement loop — O(settlements × ledger).
 *   <li>Discrepancy details were bare strings ("amount mismatch"), giving an operator no amount, no
 *       direction, and no fee, so every discrepancy required a manual Stripe lookup to triage.
 * </ul>
 *
 * <p>Matching is now two passes: exact charge-id first (authoritative), then amount-bucketed
 * fallback over whatever is left, with both sides removed from the pool as they are consumed.
 */
@Component
public class MatchingEngine {

    private final long amountToleranceMinor;

    public MatchingEngine(@Value("${payguard.reconciliation.amount-tolerance-minor:1}") long amountToleranceMinor) {
        this.amountToleranceMinor = amountToleranceMinor;
    }

    public MatchResult match(String runId, List<ExpectedPayment> expected, List<SettlementRecord> settlements) {
        List<String> matchedTransactionIds = new ArrayList<>();
        List<Discrepancy> discrepancies = new ArrayList<>();

        Map<String, ExpectedPayment> byCharge = new HashMap<>();
        for (ExpectedPayment payment : expected) {
            if (payment.getStripeChargeId() != null) {
                // A charge id maps to one payment; a duplicate means the ledger itself is corrupt,
                // so keep the first and let the second surface as MISSING_IN_SETTLEMENT.
                byCharge.putIfAbsent(payment.getStripeChargeId(), payment);
            }
        }

        Set<String> consumedTransactionIds = new HashSet<>();
        Set<String> seenChargeIds = new HashSet<>();
        List<SettlementRecord> unmatchedSettlements = new ArrayList<>();

        // Pass 1 — charge id is the authoritative join key.
        for (SettlementRecord settlement : settlements) {
            if (!seenChargeIds.add(settlement.getStripeChargeId())) {
                discrepancies.add(new Discrepancy(
                        runId,
                        settlement.getMerchantId(),
                        DiscrepancyType.DUPLICATE_SETTLEMENT,
                        null,
                        settlement.getStripeChargeId(),
                        "Stripe returned charge %s more than once in this payout window"
                                .formatted(settlement.getStripeChargeId())));
                continue;
            }

            ExpectedPayment payment = byCharge.get(settlement.getStripeChargeId());
            if (payment == null) {
                unmatchedSettlements.add(settlement);
                continue;
            }

            consumedTransactionIds.add(payment.getTransactionId());
            Discrepancy discrepancy = compare(runId, payment, settlement);
            if (discrepancy == null) {
                matchedTransactionIds.add(payment.getTransactionId());
            } else {
                discrepancies.add(discrepancy);
            }
        }

        // Pass 2 — settlements whose charge id is not in the ledger yet (webhook lost or delayed).
        // Bucketing by (merchant, rounded amount) replaces the linear rescan and, because entries
        // are popped from the deque, no ledger row can satisfy two settlements.
        Map<MerchantAmount, Deque<ExpectedPayment>> candidates = bucketUnconsumed(expected, consumedTransactionIds);

        for (SettlementRecord settlement : unmatchedSettlements) {
            ExpectedPayment candidate = takeCandidate(candidates, settlement);
            if (candidate != null) {
                consumedTransactionIds.add(candidate.getTransactionId());
                matchedTransactionIds.add(candidate.getTransactionId());
                continue;
            }
            discrepancies.add(new Discrepancy(
                    runId,
                    settlement.getMerchantId(),
                    DiscrepancyType.MISSING_IN_LEDGER,
                    null,
                    settlement.getStripeChargeId(),
                    "Stripe settled %d %s for charge %s with no matching ledger entry"
                            .formatted(
                                    settlement.getGrossMinor(),
                                    settlement.getCurrency(),
                                    settlement.getStripeChargeId())));
        }

        // Anything the ledger expected that no settlement covered.
        for (ExpectedPayment payment : expected) {
            if (consumedTransactionIds.contains(payment.getTransactionId())) {
                continue;
            }
            if (payment.getStripeChargeId() == null) {
                // Never reached Stripe (still PENDING or failed) — not a settlement gap.
                continue;
            }
            discrepancies.add(new Discrepancy(
                    runId,
                    payment.getMerchantId(),
                    DiscrepancyType.MISSING_IN_SETTLEMENT,
                    payment.getTransactionId(),
                    payment.getStripeChargeId(),
                    "ledger expects %d %s for charge %s but it was not in any payout for this date"
                            .formatted(payment.getAmountMinor(), payment.getCurrency(), payment.getStripeChargeId())));
        }

        return new MatchResult(matchedTransactionIds, discrepancies);
    }

    /** {@code null} when the pair agrees within tolerance. */
    private Discrepancy compare(String runId, ExpectedPayment payment, SettlementRecord settlement) {
        if (!payment.getCurrency().equalsIgnoreCase(settlement.getCurrency())) {
            return new Discrepancy(
                    runId,
                    payment.getMerchantId(),
                    DiscrepancyType.CURRENCY_MISMATCH,
                    payment.getTransactionId(),
                    settlement.getStripeChargeId(),
                    "ledger currency %s but Stripe settled in %s"
                            .formatted(payment.getCurrency(), settlement.getCurrency()));
        }

        long delta = settlement.getGrossMinor() - payment.getAmountMinor();
        if (Math.abs(delta) <= amountToleranceMinor) {
            return null;
        }

        if ("REFUNDED".equals(payment.getStatus())) {
            return new Discrepancy(
                    runId,
                    payment.getMerchantId(),
                    DiscrepancyType.REFUND_ADJUSTMENT,
                    payment.getTransactionId(),
                    settlement.getStripeChargeId(),
                    "refunded payment netted against settlement: ledger %d, settled %d (delta %d %s)"
                            .formatted(
                                    payment.getAmountMinor(),
                                    settlement.getGrossMinor(),
                                    delta,
                                    payment.getCurrency()));
        }

        // Direction matters for triage: under-settled is a payout shortfall to chase with Stripe,
        // over-settled usually means a duplicate charge or a misattributed transfer.
        String direction = delta < 0 ? "under-settled" : "over-settled";
        return new Discrepancy(
                runId,
                payment.getMerchantId(),
                DiscrepancyType.AMOUNT_MISMATCH,
                payment.getTransactionId(),
                settlement.getStripeChargeId(),
                "%s by %d %s: ledger expected %d, Stripe settled %d gross"
                        .formatted(
                                direction,
                                Math.abs(delta),
                                payment.getCurrency(),
                                payment.getAmountMinor(),
                                settlement.getGrossMinor()));
    }

    private Map<MerchantAmount, Deque<ExpectedPayment>> bucketUnconsumed(
            List<ExpectedPayment> expected, Set<String> consumedTransactionIds) {
        Map<MerchantAmount, Deque<ExpectedPayment>> buckets = new HashMap<>();
        for (ExpectedPayment payment : expected) {
            // Only rows Stripe has not already claimed by charge id are fallback candidates, and
            // only rows with no charge id of their own — a row that has one and did not match is a
            // genuine settlement gap, not an amount-match candidate.
            if (consumedTransactionIds.contains(payment.getTransactionId()) || payment.getStripeChargeId() != null) {
                continue;
            }
            buckets.computeIfAbsent(
                            new MerchantAmount(payment.getMerchantId(), payment.getAmountMinor()),
                            key -> new ArrayDeque<>())
                    .add(payment);
        }
        return buckets;
    }

    /**
     * Pops one ledger row matching this settlement's merchant and amount, walking the tolerance
     * window so a 1-minor-unit rounding difference still matches.
     */
    private ExpectedPayment takeCandidate(
            Map<MerchantAmount, Deque<ExpectedPayment>> candidates, SettlementRecord settlement) {
        for (long offset = 0; offset <= amountToleranceMinor; offset++) {
            for (long amount : offset == 0
                    ? new long[] {settlement.getGrossMinor()}
                    : new long[] {settlement.getGrossMinor() - offset, settlement.getGrossMinor() + offset}) {

                Deque<ExpectedPayment> bucket =
                        candidates.get(new MerchantAmount(settlement.getMerchantId(), amount));
                if (bucket == null) {
                    continue;
                }

                Iterator<ExpectedPayment> iterator = bucket.iterator();
                while (iterator.hasNext()) {
                    ExpectedPayment candidate = iterator.next();
                    if (candidate.getCurrency().equalsIgnoreCase(settlement.getCurrency())) {
                        iterator.remove();
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private record MerchantAmount(String merchantId, long amountMinor) {
    }

    public record MatchResult(List<String> matchedTransactionIds, List<Discrepancy> discrepancies) {
    }
}
