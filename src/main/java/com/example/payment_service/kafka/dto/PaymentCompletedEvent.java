package com.example.payment_service.kafka.dto;

public record PaymentCompletedEvent(
        String tripId,
        Integer fare,
        String userId
) {}