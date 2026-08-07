package com.payguard.reconciliation.matching;

import com.payguard.reconciliation.domain.Discrepancy;
import com.payguard.reconciliation.domain.DiscrepancyType;
import com.payguard.reconciliation.domain.ExpectedPayment;
import com.payguard.reconciliation.domain.SettlementRecord;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MatchingEngine {

    private final long amountToleranceMinor;

    public MatchingEngine(@Value("${payguard.reconciliation.amount-tolerance-minor:1}") long amountToleranceMinor) {
        this.amountToleranceMinor = amountToleranceMinor;
    }

    public MatchResult match(String runId, List<ExpectedPayment> expected, List<SettlementRecord> settlements) {
        Map<String, ExpectedPayment> expectedByCharge = expected.stream()
                .filter(payment -> payment.getStripeChargeId() != null)
                .collect(Collectors.toMap(ExpectedPayment::getStripeChargeId, Function.identity(), (a, b) -> a));
        Map<String, SettlementRecord> settlementByCharge =
                settlements.stream().collect(Collectors.toMap(SettlementRecord::getStripeChargeId, Function.identity()));

        List<String> matchedTransactionIds = new ArrayList<>();
        List<Discrepancy> discrepancies = new ArrayList<>();
        Set<String> matchedCharges = new HashSet<>();

        for (SettlementRecord settlement : settlements) {
            ExpectedPayment payment = expectedByCharge.get(settlement.getStripeChargeId());
            if (payment != null) {
                matchedCharges.add(settlement.getStripeChargeId());
                if (!payment.getCurrency().equalsIgnoreCase(settlement.getCurrency())) {
                    discrepancies.add(new Discrepancy(
                            runId,
                            payment.getMerchantId(),
                            DiscrepancyType.CURRENCY_MISMATCH,
                            payment.getTransactionId(),
                            settlement.getStripeChargeId(),
                            "currency mismatch"));
                } else if (Math.abs(payment.getAmountMinor() - settlement.getGrossMinor()) > amountToleranceMinor) {
                    if (payment.getStatus().equals("REFUNDED")) {
                        discrepancies.add(new Discrepancy(
                                runId,
                                payment.getMerchantId(),
                                DiscrepancyType.REFUND_ADJUSTMENT,
                                payment.getTransactionId(),
                                settlement.getStripeChargeId(),
                                "refunded payment netted against settlement"));
                    } else {
                        discrepancies.add(new Discrepancy(
                                runId,
                                payment.getMerchantId(),
                                DiscrepancyType.AMOUNT_MISMATCH,
                                payment.getTransactionId(),
                                settlement.getStripeChargeId(),
                                "amount mismatch"));
                    }
                } else {
                    matchedTransactionIds.add(payment.getTransactionId());
                }
                continue;
            }

            ExpectedPayment amountMatch = expected.stream()
                    .filter(candidate -> candidate.getMerchantId().equals(settlement.getMerchantId()))
                    .filter(candidate -> Math.abs(candidate.getAmountMinor() - settlement.getGrossMinor()) <= amountToleranceMinor)
                    .findFirst()
                    .orElse(null);
            if (amountMatch != null && amountMatch.getStripeChargeId() == null) {
                matchedTransactionIds.add(amountMatch.getTransactionId());
                matchedCharges.add(settlement.getStripeChargeId());
            } else {
                discrepancies.add(new Discrepancy(
                        runId,
                        settlement.getMerchantId(),
                        DiscrepancyType.MISSING_IN_LEDGER,
                        null,
                        settlement.getStripeChargeId(),
                        "settlement without ledger entry"));
            }
        }

        for (ExpectedPayment payment : expected) {
            if (payment.getStripeChargeId() != null && !matchedCharges.contains(payment.getStripeChargeId())) {
                discrepancies.add(new Discrepancy(
                        runId,
                        payment.getMerchantId(),
                        DiscrepancyType.MISSING_IN_SETTLEMENT,
                        payment.getTransactionId(),
                        payment.getStripeChargeId(),
                        "ledger entry without settlement"));
            }
        }

        return new MatchResult(matchedTransactionIds, discrepancies);
    }

    public record MatchResult(List<String> matchedTransactionIds, List<Discrepancy> discrepancies) {
    }
}
