package com.payguard.reconciliation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.reconciliation.domain.ExpectedPayment;
import com.payguard.reconciliation.domain.ProcessedEvent;
import com.payguard.reconciliation.repository.ExpectedPaymentRepository;
import com.payguard.reconciliation.repository.ProcessedEventRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

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

    /**
     * Applies {@code payment.completed} to the ledger.
     *
     * <p>If {@code payment.created} has not arrived yet (Kafka gives no cross-topic ordering
     * guarantee), a placeholder is created and flagged for repair rather than fabricating a
     * zero-amount USD row. A fabricated amount would silently reconcile as an
     * {@code AMOUNT_MISMATCH} against the real settlement forever.
     */
    private void upsertCompleted(JsonNode node) {
        String transactionId = text(node, "transaction_id");
        ExpectedPayment payment = expectedPayments.findByTransactionId(transactionId).orElse(null);

        if (payment == null) {
            log.warn(
                    "payment.completed for {} arrived before payment.created — creating provisional ledger entry",
                    transactionId);
            payment = new ExpectedPayment(
                    transactionId,
                    text(node, "merchant_id"),
                    amountMinor(node),
                    currency(node),
                    "AWAITING_CREATE",
                    Instant.now());
        }

        payment.complete(text(node, "stripe_charge_id"));
        expectedPayments.save(payment);
    }

    /**
     * {@code payment.completed} carries no amount, so an out-of-order arrival has none to record.
     * A negative sentinel makes the gap explicit instead of asserting the payment was for zero.
     */
    private long amountMinor(JsonNode node) {
        JsonNode value = node.get("amount_minor");
        return value == null || value.isNull() ? -1L : value.asLong();
    }

    private String currency(JsonNode node) {
        String value = text(node, "currency");
        return value == null ? "XXX" : value;
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
