package com.example.payment_service.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long tripId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long paymentMethodId;

    @Column(nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(length = 255)
    private String pgTransactionId;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime completedAt;

    @Builder
    public Payment(Long tripId, Long userId, Long paymentMethodId, Integer amount) {
        this.tripId = tripId;
        this.userId = userId;
        this.paymentMethodId = paymentMethodId;
        this.amount = amount;
        this.status = PaymentStatus.REQUESTED;
        this.requestedAt = LocalDateTime.now();
    }

    public void complete(String pgTransactionId) {
        this.status = PaymentStatus.COMPLETED;
        this.pgTransactionId = pgTransactionId;
        this.completedAt = LocalDateTime.now();
    }
}