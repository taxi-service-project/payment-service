package com.example.payment_service.kafka.dto;

public record PaymentCompletedEvent(
        Long tripId,
        Integer fare
) {}