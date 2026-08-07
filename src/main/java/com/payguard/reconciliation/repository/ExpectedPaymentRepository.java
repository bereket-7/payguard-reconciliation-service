package com.payguard.reconciliation.repository;

import com.payguard.reconciliation.domain.ExpectedPayment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpectedPaymentRepository extends JpaRepository<ExpectedPayment, UUID> {

    Optional<ExpectedPayment> findByTransactionId(String transactionId);

    List<ExpectedPayment> findByMerchantIdAndReconciledAtIsNull(String merchantId);
}
