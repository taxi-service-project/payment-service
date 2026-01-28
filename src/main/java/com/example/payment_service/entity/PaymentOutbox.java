package com.example.payment_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment_outbox", indexes = @Index(name = "idx_outbox_status_created", columnList = "status, createdAt"))
public class PaymentOutbox extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String aggregateId;

    private String topic;       // 발행할 토픽

    @Lob
    private String payload;     // 이벤트 내용 (JSON)

    @Enumerated(EnumType.STRING)
    private OutboxStatus status; // READY, PUBLISHING, DONE

    @Builder
    public PaymentOutbox(String aggregateId, String topic, String payload) {
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.payload = payload;
        this.status = OutboxStatus.READY;
    }

    public void changeStatus(OutboxStatus status) {
        this.status = status;
    }
}