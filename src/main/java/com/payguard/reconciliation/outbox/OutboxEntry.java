package com.payguard.reconciliation.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One pending domain event, written in the same transaction as the state change it describes.
 *
 * <p>Deliberately identical in shape to the payment service's {@code OutboxEntry} so both relays
 * behave the same way under failure and can be read with the same operator queries.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntry {

    /** Truncation bound for {@code last_error}; a Kafka/Avro stack trace message can be very long. */
    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Number of failed publish attempts. Drives both the backoff and the dead-letter cutoff. */
    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /** Earliest time the relay may retry this entry, so one bad payload cannot starve the batch. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** Set once the entry has exhausted its retries and been copied to the dead-letter topic. */
    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    protected OutboxEntry() {
    }

    public OutboxEntry(
            String eventId,
            String aggregateId,
            String eventType,
            String topic,
            String partitionKey,
            String payload) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (nextAttemptAt == null) {
            nextAttemptAt = createdAt;
        }
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getPayload() {
        return payload;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getDeadLetteredAt() {
        return deadLetteredAt;
    }

    public void markPublished() {
        publishedAt = Instant.now();
        lastError = null;
    }

    /** Records a failed attempt and schedules the next one after {@code backoff}. */
    public void recordFailure(String error, Duration backoff) {
        attempts++;
        lastError = truncate(error);
        nextAttemptAt = Instant.now().plus(backoff);
    }

    public void markDeadLettered(String error) {
        attempts++;
        lastError = truncate(error);
        deadLetteredAt = Instant.now();
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
