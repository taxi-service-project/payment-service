package com.example.payment_service.repository;

import com.example.payment_service.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByTripId(String tripId);

    boolean existsByTripId(String tripId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Payment p SET p.status = 'PROCESSING' WHERE p.id = :id AND p.status = 'REQUESTED'")
    int tryStartProcessing(@Param("id") Long id);

}