package com.payguard.reconciliation.stripe;

import com.payguard.reconciliation.domain.SettlementRecord;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Payout;
import com.stripe.param.PayoutListParams;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StripePayoutClient {

    public List<SettlementRecord> fetchSettlementsForDate(LocalDate date) throws StripeException {
        long start = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        long end = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        PayoutListParams params = PayoutListParams.builder()
                .setCreated(PayoutListParams.Created.builder()
                        .setGte(start)
                        .setLt(end)
                        .build())
                .setLimit(100L)
                .build();

        List<SettlementRecord> records = new ArrayList<>();
        for (Payout payout : Payout.list(params).getData()) {
            Map<String, Object> payoutParams = new HashMap<>();
            payoutParams.put("payout", payout.getId());
            payoutParams.put("limit", 100);
            for (BalanceTransaction transaction : BalanceTransaction.list(payoutParams).getData()) {
                if (transaction.getSource() == null) {
                    continue;
                }
                records.add(new SettlementRecord(
                        payout.getId(),
                        transaction.getSource(),
                        transaction.getMetadata().getOrDefault("merchant_id", "unknown"),
                        transaction.getAmount(),
                        transaction.getFee(),
                        transaction.getNet(),
                        transaction.getCurrency().toUpperCase(),
                        Instant.ofEpochSecond(transaction.getCreated()),
                        transaction.toJson()));
            }
        }
        return records;
    }
}
