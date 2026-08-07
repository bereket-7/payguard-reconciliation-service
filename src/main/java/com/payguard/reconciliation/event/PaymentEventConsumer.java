package com.payguard.reconciliation.event;

import com.payguard.reconciliation.service.LedgerService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private final LedgerService ledgerService;

    public PaymentEventConsumer(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @KafkaListener(
            topics = {"payment.created", "payment.completed", "payment.failed"},
            groupId = "reconciliation-service")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        ledgerService.handle(record.topic(), record.value());
    }
}
