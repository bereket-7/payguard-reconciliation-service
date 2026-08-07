package com.payguard.reconciliation.outbox;

import com.payguard.events.reconciliation.ReconciliationCompleted;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationEventPublisher {

    private final KafkaTemplate<String, SpecificRecord> kafkaTemplate;

    public ReconciliationEventPublisher(KafkaTemplate<String, SpecificRecord> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishCompleted(String settlementId, String merchantId, String matchStatus, int discrepancyCount) {
        try {
            ReconciliationCompleted event = new ReconciliationCompleted();
            event.put("event_id", UUID.randomUUID().toString());
            event.put("settlement_id", settlementId);
            event.put("merchant_id", merchantId);
            event.put("match_status", matchStatus);
            event.put("discrepancy_count", discrepancyCount);
            event.put("occurred_at", Instant.now().toString());
            kafkaTemplate.send("reconciliation.completed", merchantId, event);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to publish reconciliation.completed", ex);
        }
    }
}
