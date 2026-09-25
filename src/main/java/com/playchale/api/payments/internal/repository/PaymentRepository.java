package com.playchale.api.payments.internal.repository;

import java.util.Optional;
import java.util.UUID;

import com.playchale.api.payments.internal.domain.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

	/** The payment, locked until the transaction ends, so two status checks at once can't both settle it. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Payment p where p.id = :id")
	Optional<Payment> lockById(UUID id);

}
