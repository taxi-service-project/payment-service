package com.example.payment_service.kafka.dto;

import java.time.LocalDateTime;

public record TripCompletedEvent(
        Long tripId,
        Long userId,
        Integer distanceMeters,
        Integer durationSeconds,
        LocalDateTime endedAt
) {}