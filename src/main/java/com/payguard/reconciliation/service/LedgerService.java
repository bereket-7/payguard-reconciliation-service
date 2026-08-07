package com.payguard.reconciliation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.reconciliation.domain.ExpectedPayment;
import com.payguard.reconciliation.domain.ProcessedEvent;
import com.payguard.reconciliation.repository.ExpectedPaymentRepository;
import com.payguard.reconciliation.repository.ProcessedEventRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private final ExpectedPaymentRepository expectedPayments;
    private final ProcessedEventRepository processedEvents;
    private final ObjectMapper objectMapper;

    public LedgerService(
            ExpectedPaymentRepository expectedPayments,
            ProcessedEventRepository processedEvents,
            ObjectMapper objectMapper) {
        this.expectedPayments = expectedPayments;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void handle(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String eventId = text(node, "event_id");
            if (eventId == null || processedEvents.existsById(eventId)) {
                return;
            }
            switch (topic) {
                case "payment.created" -> upsertCreated(node);
                case "payment.completed" -> upsertCompleted(node);
                case "payment.failed" -> markFailed(node);
                default -> {
                    return;
                }
            }
            processedEvents.save(new ProcessedEvent(eventId));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to update ledger from topic " + topic, ex);
        }
    }

    private void upsertCreated(JsonNode node) {
        String transactionId = text(node, "transaction_id");
        ExpectedPayment payment = expectedPayments.findByTransactionId(transactionId).orElseGet(() -> new ExpectedPayment(
                transactionId,
                text(node, "merchant_id"),
                node.get("amount_minor").asLong(),
                text(node, "currency"),
                "PENDING",
                Instant.now()));
        expectedPayments.save(payment);
    }

    private void upsertCompleted(JsonNode node) {
        String transactionId = text(node, "transaction_id");
        ExpectedPayment payment = expectedPayments.findByTransactionId(transactionId).orElseGet(() -> new ExpectedPayment(
                transactionId,
                text(node, "merchant_id"),
                0,
                "USD",
                "COMPLETED",
                Instant.now()));
        payment.complete(text(node, "stripe_charge_id"));
        expectedPayments.save(payment);
    }

    private void markFailed(JsonNode node) {
        expectedPayments.findByTransactionId(text(node, "transaction_id")).ifPresent(payment -> {
            // keep ledger row for audit; refunds/disputes handled separately
        });
    }

    @Transactional
    public void applyRefund(String transactionId) {
        expectedPayments.findByTransactionId(transactionId).ifPresent(payment -> {
            payment.applyRefund();
            expectedPayments.save(payment);
        });
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
