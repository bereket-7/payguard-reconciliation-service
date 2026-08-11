package com.payguard.reconciliation.outbox;

import com.payguard.reconciliation.kafka.JsonAvroConverter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the reconciliation outbox to Kafka.
 *
 * <p>Behaviourally identical to the payment service's relay: per-entry isolation, exponential
 * backoff, and dead-lettering to {@code <topic>.dlq} once an entry exhausts its attempts.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final String DLQ_SUFFIX = ".dlq";

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, SpecificRecord> kafkaTemplate;
    private final KafkaTemplate<String, String> dlqKafkaTemplate;
    private final JsonAvroConverter jsonAvroConverter;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final long sendTimeoutMs;
    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Counter deadLetteredCounter;

    public OutboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, SpecificRecord> kafkaTemplate,
            KafkaTemplate<String, String> dlqKafkaTemplate,
            JsonAvroConverter jsonAvroConverter,
            MeterRegistry meterRegistry,
            @Value("${payguard.outbox.batch-size:50}") int batchSize,
            @Value("${payguard.outbox.max-attempts:8}") int maxAttempts,
            @Value("${payguard.outbox.base-backoff-ms:1000}") long baseBackoffMs,
            @Value("${payguard.outbox.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.dlqKafkaTemplate = dlqKafkaTemplate;
        this.jsonAvroConverter = jsonAvroConverter;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = Duration.ofMillis(baseBackoffMs);
        this.sendTimeoutMs = sendTimeoutMs;
        this.publishedCounter = Counter.builder("payguard.outbox.published")
                .description("Outbox entries published to Kafka")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("payguard.outbox.publish.failed")
                .description("Outbox publish attempts that failed and will be retried")
                .register(meterRegistry);
        this.deadLetteredCounter = Counter.builder("payguard.outbox.dead.lettered")
                .description("Outbox entries that exhausted their retries and were dead-lettered")
                .register(meterRegistry);
        meterRegistry.gauge(
                "payguard.outbox.backlog",
                repository,
                OutboxRepository::countByPublishedAtIsNullAndDeadLetteredAtIsNull);
        meterRegistry.gauge(
                "payguard.outbox.dead.letter.depth", repository, OutboxRepository::countByDeadLetteredAtIsNotNull);
    }

    /**
     * Publishes one batch of pending events.
     *
     * <p>Each entry is isolated: a failure updates only that entry's retry state and the loop
     * continues, so a single unconvertible payload cannot roll back the {@code markPublished()} of
     * the entries that already succeeded alongside it.
     */
    @Scheduled(fixedDelayString = "${payguard.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEntry> batch = repository.lockPublishable(Instant.now(), batchSize);
        for (OutboxEntry entry : batch) {
            try {
                SpecificRecord record = jsonAvroConverter.toRecord(entry.getTopic(), entry.getPayload());
                kafkaTemplate
                        .send(entry.getTopic(), entry.getPartitionKey(), record)
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                entry.markPublished();
                publishedCounter.increment();
                log.debug("Published outbox event {} to topic {}", entry.getEventId(), entry.getTopic());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                handleFailure(entry, ex);
                return;
            } catch (Exception ex) {
                handleFailure(entry, ex);
            }
        }
    }

    private void handleFailure(OutboxEntry entry, Exception ex) {
        String reason = ex.getClass().getSimpleName() + ": " + ex.getMessage();

        if (entry.getAttempts() + 1 >= maxAttempts) {
            deadLetter(entry, reason, ex);
            return;
        }

        Duration backoff = backoffFor(entry.getAttempts());
        entry.recordFailure(reason, backoff);
        failedCounter.increment();
        log.warn(
                "Failed to publish outbox event {} to topic {} (attempt {}/{}), retrying in {}",
                entry.getEventId(),
                entry.getTopic(),
                entry.getAttempts(),
                maxAttempts,
                backoff,
                ex);
    }

    /**
     * Copies an exhausted entry to {@code <topic>.dlq} as its original JSON, then marks it so the
     * relay moves on. The DLQ producer is String-valued because the most common reason an entry gets
     * here is that its payload cannot be turned into an Avro record at all.
     */
    private void deadLetter(OutboxEntry entry, String reason, Exception cause) {
        String dlqTopic = entry.getTopic() + DLQ_SUFFIX;
        try {
            dlqKafkaTemplate
                    .send(dlqTopic, entry.getPartitionKey(), entry.getPayload())
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            // Leave the entry retryable rather than dropping it — the payload is still only in the DB.
            entry.recordFailure("dead-letter publish interrupted: " + reason, backoffFor(entry.getAttempts()));
            failedCounter.increment();
            return;
        } catch (Exception ex) {
            entry.recordFailure("dead-letter publish failed: " + reason, backoffFor(entry.getAttempts()));
            failedCounter.increment();
            log.error(
                    "Could not dead-letter outbox event {} to {}; keeping it queued for retry",
                    entry.getEventId(),
                    dlqTopic,
                    ex);
            return;
        }

        entry.markDeadLettered(reason);
        deadLetteredCounter.increment();
        log.error(
                "Outbox event {} exhausted {} attempts and was dead-lettered to {}: {}",
                entry.getEventId(),
                maxAttempts,
                dlqTopic,
                reason,
                cause);
    }

    private Duration backoffFor(int attempts) {
        // 1s, 2s, 4s, ... capped, so a broken schema registry does not become a hot loop.
        long multiplier = 1L << Math.min(attempts, 20);
        Duration backoff = baseBackoff.multipliedBy(multiplier);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }
}
