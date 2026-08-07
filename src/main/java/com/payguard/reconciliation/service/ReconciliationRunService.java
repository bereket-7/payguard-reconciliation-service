package com.payguard.reconciliation.service;

import com.payguard.reconciliation.domain.Discrepancy;
import com.payguard.reconciliation.domain.ExpectedPayment;
import com.payguard.reconciliation.domain.ReconciliationRun;
import com.payguard.reconciliation.domain.SettlementRecord;
import com.payguard.reconciliation.matching.MatchingEngine;
import com.payguard.reconciliation.outbox.ReconciliationEventPublisher;
import com.payguard.reconciliation.repository.DiscrepancyRepository;
import com.payguard.reconciliation.repository.ExpectedPaymentRepository;
import com.payguard.reconciliation.repository.ReconciliationRunRepository;
import com.payguard.reconciliation.repository.SettlementRecordRepository;
import com.payguard.reconciliation.stripe.StripePayoutClient;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationRunService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunService.class);

    private final ReconciliationRunRepository runs;
    private final ExpectedPaymentRepository expectedPayments;
    private final SettlementRecordRepository settlementRecords;
    private final DiscrepancyRepository discrepancies;
    private final MatchingEngine matchingEngine;
    private final StripePayoutClient stripePayoutClient;
    private final ReconciliationEventPublisher publisher;
    private final ConcurrentHashMap<LocalDate, Boolean> locks = new ConcurrentHashMap<>();

    public ReconciliationRunService(
            ReconciliationRunRepository runs,
            ExpectedPaymentRepository expectedPayments,
            SettlementRecordRepository settlementRecords,
            DiscrepancyRepository discrepancies,
            MatchingEngine matchingEngine,
            StripePayoutClient stripePayoutClient,
            ReconciliationEventPublisher publisher) {
        this.runs = runs;
        this.expectedPayments = expectedPayments;
        this.settlementRecords = settlementRecords;
        this.discrepancies = discrepancies;
        this.matchingEngine = matchingEngine;
        this.stripePayoutClient = stripePayoutClient;
        this.publisher = publisher;
    }

    @Scheduled(cron = "${payguard.reconciliation.daily-cron:0 30 2 * * *}")
    public void scheduledRun() {
        runForDate(LocalDate.now().minusDays(1));
    }

    @Transactional
    public ReconciliationRun runForDate(LocalDate settlementDate) {
        if (locks.putIfAbsent(settlementDate, Boolean.TRUE) != null) {
            throw new IllegalStateException("run already in progress for " + settlementDate);
        }
        try {
            String runId = UUID.randomUUID().toString();
            ReconciliationRun run = runs.save(new ReconciliationRun(runId, settlementDate));
            List<SettlementRecord> settlements = stripePayoutClient.fetchSettlementsForDate(settlementDate);
            for (SettlementRecord settlement : settlements) {
                if (settlementRecords.findByStripeChargeId(settlement.getStripeChargeId()).isEmpty()) {
                    settlementRecords.save(settlement);
                }
            }
            List<ExpectedPayment> expected = expectedPayments.findAll();
            MatchingEngine.MatchResult result = matchingEngine.match(runId, expected, settlements);
            discrepancies.saveAll(result.discrepancies());
            for (String transactionId : result.matchedTransactionIds()) {
                expectedPayments.findByTransactionId(transactionId).ifPresent(payment -> {
                    payment.markReconciled(runId);
                    expectedPayments.save(payment);
                });
            }
            run.complete(expected.size(), settlements.size(), result.matchedTransactionIds().size(), result.discrepancies().size());
            runs.save(run);
            publishPerMerchant(run, result);
            log.info(
                    "Completed reconciliation run {} for {} with {} discrepancies",
                    runId,
                    settlementDate,
                    result.discrepancies().size());
            return run;
        } catch (Exception ex) {
            throw new IllegalStateException("reconciliation run failed", ex);
        } finally {
            locks.remove(settlementDate);
        }
    }

    private void publishPerMerchant(ReconciliationRun run, MatchingEngine.MatchResult result) {
        Map<String, Integer> discrepancyCounts = new HashMap<>();
        for (Discrepancy discrepancy : result.discrepancies()) {
            discrepancyCounts.merge(discrepancy.getMerchantId(), 1, Integer::sum);
        }
        Map<String, Boolean> merchants = new HashMap<>();
        expectedPayments.findAll().forEach(payment -> merchants.put(payment.getMerchantId(), true));
        discrepancyCounts.keySet().forEach(merchantId -> merchants.put(merchantId, true));

        for (String merchantId : merchants.keySet()) {
            int discrepancyCount = discrepancyCounts.getOrDefault(merchantId, 0);
            String matchStatus = discrepancyCount == 0 ? "MATCHED" : discrepancyCount < 3 ? "PARTIAL" : "UNMATCHED";
            publisher.publishCompleted(run.getRunId(), merchantId, matchStatus, discrepancyCount);
        }
    }
}
