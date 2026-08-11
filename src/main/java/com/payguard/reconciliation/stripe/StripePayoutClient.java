package com.payguard.reconciliation.stripe;

import com.payguard.reconciliation.domain.SettlementRecord;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Payout;
import com.stripe.param.BalanceTransactionListParams;
import com.stripe.param.PayoutListParams;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads a day of Stripe payouts and the balance transactions inside them.
 *
 * <p>Two correctness fixes over the previous version:
 *
 * <ul>
 *   <li>Both list calls were single-page with {@code limit=100} and {@code getData()}, so any
 *       account with more than 100 payouts in a day — or a payout containing more than 100 charges,
 *       which is routine — silently dropped the remainder. Reconciliation then reported the missing
 *       charges as {@code MISSING_IN_SETTLEMENT} against real, settled money. Autopaging walks the
 *       full result set.
 *   <li>The day boundary was hardcoded to UTC while Stripe computes payout dates in the account's
 *       timezone; for a non-UTC account every run reconciled a window offset from the one Stripe
 *       settled, producing a standing set of phantom discrepancies at both edges.
 * </ul>
 */
@Component
public class StripePayoutClient {

    private static final Logger log = LoggerFactory.getLogger(StripePayoutClient.class);

    private static final long PAGE_SIZE = 100L;

    private final ZoneId settlementZone;

    public StripePayoutClient(
            @Value("${payguard.reconciliation.settlement-zone:UTC}") String settlementZone) {
        this.settlementZone = ZoneId.of(settlementZone);
    }

    public List<SettlementRecord> fetchSettlementsForDate(LocalDate date) throws StripeException {
        long start = date.atStartOfDay(settlementZone).toEpochSecond();
        long end = date.plusDays(1).atStartOfDay(settlementZone).toEpochSecond();

        PayoutListParams params = PayoutListParams.builder()
                .setCreated(PayoutListParams.Created.builder()
                        .setGte(start)
                        .setLt(end)
                        .build())
                .setLimit(PAGE_SIZE)
                .build();

        List<SettlementRecord> records = new ArrayList<>();
        int payoutCount = 0;

        for (Payout payout : Payout.list(params).autoPagingIterable()) {
            payoutCount++;
            BalanceTransactionListParams transactionParams = BalanceTransactionListParams.builder()
                    .setPayout(payout.getId())
                    .setLimit(PAGE_SIZE)
                    .build();

            for (BalanceTransaction transaction : BalanceTransaction.list(transactionParams).autoPagingIterable()) {
                if (transaction.getSource() == null) {
                    // Payout fees, transfers and adjustments have no source charge; they are
                    // accounted for on the payout, not against an expected payment.
                    continue;
                }
                records.add(new SettlementRecord(
                        payout.getId(),
                        transaction.getSource(),
                        merchantId(transaction),
                        transaction.getAmount(),
                        transaction.getFee(),
                        transaction.getNet(),
                        transaction.getCurrency().toUpperCase(Locale.ROOT),
                        Instant.ofEpochSecond(transaction.getCreated()),
                        transaction.toJson()));
            }
        }

        log.info(
                "Fetched {} settlement records across {} payouts for {} ({})",
                records.size(),
                payoutCount,
                date,
                settlementZone);
        return records;
    }

    /**
     * {@code getMetadata()} is null on balance transactions that Stripe did not create from one of
     * our PaymentIntents; the old {@code getMetadata().getOrDefault(...)} NPE'd on those and aborted
     * the whole run.
     */
    private String merchantId(BalanceTransaction transaction) {
        if (transaction.getMetadata() == null) {
            return "unknown";
        }
        return transaction.getMetadata().getOrDefault("merchant_id", "unknown");
    }
}
