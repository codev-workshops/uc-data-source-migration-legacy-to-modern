package com.workshop.loanservice.repository;

import com.workshop.loanservice.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByExternalId(String externalId);

    List<Payment> findByLoanAccountId(Long loanAccountId);

    List<Payment> findByLoanAccountIdOrderByPaymentDateDesc(Long loanAccountId);

    List<Payment> findByLoanAccountAccountNumberOrderByPaymentDateDesc(String accountNumber);
}
