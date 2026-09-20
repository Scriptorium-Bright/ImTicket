package org.example.ticket.payment.repository;

import org.example.ticket.payment.model.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    Optional<PaymentAttempt> findByProviderTransactionId(String providerTransactionId);

    Optional<PaymentAttempt> findTopByPaymentOrderIdOrderByCreatedAtDesc(Long paymentOrderId);

    List<PaymentAttempt> findByPaymentOrderIdOrderByIdAsc(Long paymentOrderId);
}
