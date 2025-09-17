package com.example.payment_service.dto;

import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import java.time.LocalDateTime;

public record PaymentResponse(
        String paymentId,
        String tripId,
        Integer amount,
        PaymentStatus status,
        String pgTransactionId,
        LocalDateTime requestedAt,
        LocalDateTime completedAt
) {
    public static PaymentResponse fromEntity(Payment payment) {
        return new PaymentResponse(
                payment.getPaymentId(),
                payment.getTripId(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getPgTransactionId(),
                payment.getRequestedAt(),
                payment.getCompletedAt()
        );
    }
}