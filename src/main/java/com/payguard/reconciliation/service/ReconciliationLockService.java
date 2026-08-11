package com.payguard.reconciliation.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cluster-wide mutual exclusion for reconciliation runs.
 *
 * <p>Uses a Postgres session-level advisory lock keyed by settlement date. The previous in-JVM
 * {@code ConcurrentHashMap} guard only excluded threads within one pod, so a two-replica
 * deployment ran the same settlement date twice — duplicating discrepancies and racing ledger
 * updates.
 *
 * <p>{@code pg_try_advisory_xact_lock} is released automatically when the transaction ends, so a
 * crashed pod cannot strand the lock the way a table row would.
 */
@Service
public class ReconciliationLockService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationLockService.class);

    /** Namespace so these locks cannot collide with other advisory-lock users. */
    private static final int LOCK_NAMESPACE = 0x50475244;

    private final EntityManager entityManager;

    public ReconciliationLockService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Attempts to claim the run slot for a settlement date.
     *
     * <p>Must be called inside the same transaction that performs the run so the lock is held for
     * its full duration.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryAcquire(LocalDate settlementDate) {
        Object acquired = entityManager
                .createNativeQuery("select pg_try_advisory_xact_lock(:namespace, :key)")
                .setParameter("namespace", LOCK_NAMESPACE)
                .setParameter("key", (int) settlementDate.toEpochDay())
                .getSingleResult();

        boolean granted = Boolean.TRUE.equals(acquired);
        if (!granted) {
            log.info("Reconciliation for {} is already running on another replica", settlementDate);
        }
        return granted;
    }
}
