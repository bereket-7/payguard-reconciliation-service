package com.payguard.reconciliation.repository;

import com.payguard.reconciliation.domain.ExpectedPayment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpectedPaymentRepository extends JpaRepository<ExpectedPayment, UUID> {

    Optional<ExpectedPayment> findByTransactionId(String transactionId);

    List<ExpectedPayment> findByMerchantIdAndReconciledAtIsNull(String merchantId);

    /**
     * The candidate ledger slice for one settlement date.
     *
     * <p>The run used to call {@code findAll()}: every expected payment ever recorded, loaded into
     * one heap-resident list and then scanned once per settlement. That is O(ledger × settlements)
     * work over an unbounded result set — it OOMs long before the ledger reaches a year of volume.
     *
     * <p>The window reaches back before the settlement date because Stripe settles a charge days
     * after it is created, and excludes rows an earlier run already reconciled so they cannot be
     * matched a second time.
     */
    @Query("select p from ExpectedPayment p "
            + "where p.expectedAt >= :from and p.expectedAt < :to and p.reconciledAt is null "
            + "order by p.expectedAt asc")
    List<ExpectedPayment> findUnreconciledInWindow(
            @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
