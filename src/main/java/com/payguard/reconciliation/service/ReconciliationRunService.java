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
import com.stripe.exception.StripeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationRunService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunService.class);

    /**
     * How far back the ledger window reaches. Stripe settles a charge days after it is created, and
     * disputes and delayed payouts stretch that further, so a window that started at the settlement
     * date would report every late settlement as {@code MISSING_IN_LEDGER}.
     */
    private static final Duration LATE_SETTLEMENT_LOOKBACK = Duration.ofDays(30);

    /**
     * Hard cap on ledger rows per run. Beyond this the run is truncated and logged rather than
     * silently reconciling a partial ledger — an unbounded load is what made the previous
     * {@code findAll()} an OOM waiting to happen.
     */
    private static final int LEDGER_PAGE_LIMIT = 20_000;

    private final ReconciliationRunRepository runs;
    private final ExpectedPaymentRepository expectedPayments;
    private final SettlementRecordRepository settlementRecords;
    private final DiscrepancyRepository discrepancies;
    private final MatchingEngine matchingEngine;
    private final StripePayoutClient stripePayoutClient;
    private final ReconciliationEventPublisher publisher;
    private final ReconciliationLockService lockService;
    private final TransactionTemplate transactionTemplate;
    private final ZoneId settlementZone;

    public ReconciliationRunService(
            ReconciliationRunRepository runs,
            ExpectedPaymentRepository expectedPayments,
            SettlementRecordRepository settlementRecords,
            DiscrepancyRepository discrepancies,
            MatchingEngine matchingEngine,
            StripePayoutClient stripePayoutClient,
            ReconciliationEventPublisher publisher,
            ReconciliationLockService lockService,
            PlatformTransactionManager transactionManager,
            @Value("${payguard.reconciliation.settlement-zone:UTC}") String settlementZone) {
        this.runs = runs;
        this.expectedPayments = expectedPayments;
        this.settlementRecords = settlementRecords;
        this.discrepancies = discrepancies;
        this.matchingEngine = matchingEngine;
        this.stripePayoutClient = stripePayoutClient;
        this.publisher = publisher;
        this.lockService = lockService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.settlementZone = ZoneId.of(settlementZone);
    }

    @Scheduled(cron = "${payguard.reconciliation.daily-cron:0 30 2 * * *}")
    public void scheduledRun() {
        // "Yesterday" must be resolved in the Stripe account timezone, not the pod's default, or a
        // pod in a different region reconciles the wrong day.
        LocalDate yesterday = LocalDate.now(settlementZone).minusDays(1);
        try {
            runForDate(yesterday);
        } catch (ReconciliationInProgressException ex) {
            // Expected on multi-replica deployments: another pod won the lock. Not an error.
            log.info("Skipping scheduled run for {} — another replica holds the lock", yesterday);
        } catch (RuntimeException ex) {
            // The scheduler swallows exceptions silently otherwise, so a permanently failing nightly
            // run would leave no trace beyond missing rows.
            log.error("Scheduled reconciliation run for {} failed", yesterday, ex);
        }
    }

    /**
     * Reconciles one settlement date.
     *
     * <p>Stripe is read <em>before</em> the transaction opens. Holding a database transaction across
     * a paginated third-party API call would pin a pooled connection for the length of the fetch and
     * inflate the advisory lock's hold time to match; the fetch is a read-only, idempotent operation
     * so it is safe for a losing replica to do it and then discover it lost the lock.
     */
    public ReconciliationRun runForDate(LocalDate settlementDate) {
        List<SettlementRecord> settlements = fetchSettlements(settlementDate);
        return transactionTemplate.execute(status -> reconcile(settlementDate, settlements));
    }

    private List<SettlementRecord> fetchSettlements(LocalDate settlementDate) {
        try {
            return stripePayoutClient.fetchSettlementsForDate(settlementDate);
        } catch (StripeException ex) {
            throw new IllegalStateException("failed to fetch Stripe settlements for " + settlementDate, ex);
        }
    }

    private ReconciliationRun reconcile(LocalDate settlementDate, List<SettlementRecord> settlements) {
        // Cluster-wide guard. The in-JVM ConcurrentHashMap this replaces only excluded threads
        // inside one pod, so a two-replica deployment ran the same date twice: duplicate
        // discrepancy rows and two runs racing to mark the same payments reconciled.
        if (!lockService.tryAcquire(settlementDate)) {
            throw new ReconciliationInProgressException(settlementDate);
        }

        String runId = UUID.randomUUID().toString();
        ReconciliationRun run = runs.save(new ReconciliationRun(runId, settlementDate));

        persistNewSettlements(settlements);

        List<ExpectedPayment> expected = loadLedgerForWindow(settlementDate);
        MatchingEngine.MatchResult result = matchingEngine.match(runId, expected, settlements);

        discrepancies.saveAll(result.discrepancies());
        markReconciled(runId, expected, result.matchedTransactionIds());

        run.complete(
                expected.size(),
                settlements.size(),
                result.matchedTransactionIds().size(),
                result.discrepancies().size());
        runs.save(run);

        publishPerMerchant(run, expected, settlements, result);

        log.info(
                "Completed reconciliation run {} for {}: {} expected, {} settled, {} matched, {} discrepancies",
                runId,
                settlementDate,
                expected.size(),
                settlements.size(),
                result.matchedTransactionIds().size(),
                result.discrepancies().size());
        return run;
    }

    private void persistNewSettlements(List<SettlementRecord> settlements) {
        Set<String> seen = new HashSet<>();
        for (SettlementRecord settlement : settlements) {
            if (!seen.add(settlement.getStripeChargeId())) {
                // Stripe returned the same charge twice in this window; the unique index on
                // stripe_charge_id would abort the whole run on the second insert.
                continue;
            }
            if (settlementRecords.findByStripeChargeId(settlement.getStripeChargeId()).isEmpty()) {
                settlementRecords.save(settlement);
            }
        }
    }

    private List<ExpectedPayment> loadLedgerForWindow(LocalDate settlementDate) {
        Instant to = settlementDate.plusDays(1).atStartOfDay(settlementZone).toInstant();
        Instant from = to.minus(LATE_SETTLEMENT_LOOKBACK);

        List<ExpectedPayment> expected =
                expectedPayments.findUnreconciledInWindow(from, to, PageRequest.of(0, LEDGER_PAGE_LIMIT));

        if (expected.size() == LEDGER_PAGE_LIMIT) {
            log.warn(
                    "Ledger window {}..{} hit the {}-row cap; older unreconciled payments were not "
                            + "considered for run of {} and may be reported as settlement gaps",
                    from,
                    to,
                    LEDGER_PAGE_LIMIT,
                    settlementDate);
        }
        return expected;
    }

    private void markReconciled(String runId, List<ExpectedPayment> expected, List<String> matchedTransactionIds) {
        // The matched rows are already loaded and managed in this transaction, so re-querying each
        // one by transaction id (as the old loop did) is a wasted round trip per match.
        Map<String, ExpectedPayment> byTransactionId = new HashMap<>();
        for (ExpectedPayment payment : expected) {
            byTransactionId.put(payment.getTransactionId(), payment);
        }

        for (String transactionId : matchedTransactionIds) {
            ExpectedPayment payment = byTransactionId.get(transactionId);
            if (payment != null) {
                payment.markReconciled(runId);
            }
        }
    }

    private void publishPerMerchant(
            ReconciliationRun run,
            List<ExpectedPayment> expected,
            List<SettlementRecord> settlements,
            MatchingEngine.MatchResult result) {

        Map<String, Integer> discrepancyCounts = new HashMap<>();
        for (Discrepancy discrepancy : result.discrepancies()) {
            discrepancyCounts.merge(discrepancy.getMerchantId(), 1, Integer::sum);
        }

        // Derived from the slice this run actually examined. The old code called findAll() a second
        // time and notified every merchant that had ever transacted — including merchants with
        // nothing in this window, who got a "MATCHED" report for a run that never looked at them.
        Set<String> merchants = new LinkedHashSet<>();
        expected.forEach(payment -> merchants.add(payment.getMerchantId()));
        settlements.forEach(settlement -> merchants.add(settlement.getMerchantId()));
        merchants.addAll(discrepancyCounts.keySet());

        for (String merchantId : merchants) {
            int discrepancyCount = discrepancyCounts.getOrDefault(merchantId, 0);
            String matchStatus = discrepancyCount == 0 ? "MATCHED" : discrepancyCount < 3 ? "PARTIAL" : "UNMATCHED";
            publisher.publishCompleted(run.getRunId(), merchantId, matchStatus, discrepancyCount);
        }
    }
}
