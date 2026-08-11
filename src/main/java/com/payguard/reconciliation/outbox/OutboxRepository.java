package com.payguard.reconciliation.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    /**
     * Claims a batch of publishable entries for this relay instance.
     *
     * <p>{@code for update skip locked} keeps concurrent replicas off each other's rows — the
     * reconciliation service is deployed multi-replica for the very reason the run needs an advisory
     * lock, so the relay has to assume it is not alone.
     */
    @Query(
            value =
                    "select * from outbox where published_at is null and dead_lettered_at is null "
                            + "and next_attempt_at <= :now order by next_attempt_at, created_at "
                            + "for update skip locked limit :limit",
            nativeQuery = true)
    List<OutboxEntry> lockPublishable(@Param("now") Instant now, @Param("limit") int limit);

    long countByPublishedAtIsNullAndDeadLetteredAtIsNull();

    long countByDeadLetteredAtIsNotNull();
}
