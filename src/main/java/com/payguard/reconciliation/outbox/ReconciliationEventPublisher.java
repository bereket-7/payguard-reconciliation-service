package com.payguard.reconciliation.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stages {@code reconciliation.completed} events in the outbox.
 *
 * <p>This used to call {@code kafkaTemplate.send()} from inside {@code ReconciliationRunService}'s
 * transaction — a dual write. A rollback after the sends meant merchants held a settlement report
 * for a run that no longer existed in the database; a broker outage threw out of a run whose
 * discrepancies were already written, leaving the reconciliation durable but permanently unreported
 * because nothing retried. Writing a row in the caller's transaction makes the event as durable, and
 * as conditional, as the run itself, and hands retry and dead-lettering to {@link OutboxRelay}.
 *
 * <p>No {@code @Transactional} here on purpose: this must join the caller's transaction rather than
 * define its own, and the caller ({@code ReconciliationRunService.reconcile}) already runs inside
 * one. A {@code REQUIRES_NEW} would reintroduce exactly the split-commit this replaces.
 */
@Component
public class ReconciliationEventPublisher {

    public static final String TOPIC = "reconciliation.completed";

    private static final String EVENT_TYPE = "ReconciliationCompleted";

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public ReconciliationEventPublisher(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    public void publishCompleted(String settlementId, String merchantId, String matchStatus, int discrepancyCount) {
        // LinkedHashMap, not Map.of: the field order matches the Avro schema, which makes the stored
        // payload readable against the contract when an operator inspects a stuck row.
        Map<String, Object> payload = new LinkedHashMap<>();
        String eventId = UUID.randomUUID().toString();
        payload.put("event_id", eventId);
        payload.put("settlement_id", settlementId);
        payload.put("merchant_id", merchantId);
        payload.put("match_status", matchStatus);
        payload.put("discrepancy_count", discrepancyCount);
        payload.put("occurred_at", Instant.now().toString());

        outbox.save(new OutboxEntry(
                eventId, settlementId, EVENT_TYPE, TOPIC, merchantId, serialize(payload, settlementId, merchantId)));
    }

    private String serialize(Map<String, Object> payload, String settlementId, String merchantId) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            // Fail the run rather than persist a row the relay can never publish.
            throw new IllegalStateException(
                    "failed to serialize " + TOPIC + " for run " + settlementId + " merchant " + merchantId, ex);
        }
    }
}
