package com.example.payment_service.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "failed_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FailedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = true, length = 500)
    private String errorMessage;

    @Builder
    public FailedEvent(String payload, String topic, String errorMessage) {
        this.payload = payload;
        this.topic = topic;
        this.errorMessage = errorMessage;
    }
}