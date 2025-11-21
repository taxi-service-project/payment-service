package com.example.payment_service.kafka.dto;

public record PaymentFailedEvent(
        String tripId,
        String reason
) {}
