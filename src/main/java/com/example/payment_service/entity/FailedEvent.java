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
public class FailedEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(name = "kafka_key", nullable = true)
    private String kafkaKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = true, length = 1000)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FailedEventStatus status;

    @Builder
    public FailedEvent(String topic, String kafkaKey, String payload, String errorMessage, FailedEventStatus status) {
        this.topic = topic;
        this.kafkaKey = kafkaKey;
        this.payload = payload;
        this.errorMessage = errorMessage;
        this.status = status == null ? FailedEventStatus.PENDING : status;
    }
}