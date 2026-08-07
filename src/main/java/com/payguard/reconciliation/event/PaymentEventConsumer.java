package com.payguard.reconciliation.event;

import com.payguard.reconciliation.kafka.AvroJsonConverter;
import com.payguard.reconciliation.service.LedgerService;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private final LedgerService ledgerService;
    private final AvroJsonConverter avroJsonConverter;

    public PaymentEventConsumer(LedgerService ledgerService, AvroJsonConverter avroJsonConverter) {
        this.ledgerService = ledgerService;
        this.avroJsonConverter = avroJsonConverter;
    }

    @KafkaListener(
            topics = {"payment.created", "payment.completed", "payment.failed"},
            groupId = "reconciliation-service",
            containerFactory = "avroKafkaListenerContainerFactory")
    public void onPaymentEvent(ConsumerRecord<String, SpecificRecord> record) throws Exception {
        ledgerService.handle(record.topic(), avroJsonConverter.toJson(record.value()));
    }
}
