package com.payguard.reconciliation.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public ReconciliationEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishCompleted(String settlementId, String merchantId, String matchStatus, int discrepancyCount) {
        try {
            Map<String, Object> payload = Map.of(
                    "event_id", UUID.randomUUID().toString(),
                    "settlement_id", settlementId,
                    "merchant_id", merchantId,
                    "match_status", matchStatus,
                    "discrepancy_count", discrepancyCount,
                    "occurred_at", Instant.now().toString());
            kafkaTemplate.send(
                    "reconciliation.completed",
                    merchantId,
                    objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to publish reconciliation.completed", ex);
        }
    }
}
