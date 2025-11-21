package com.example.payment_service.kafka;

import com.example.payment_service.kafka.dto.PaymentCompletedEvent;
import com.example.payment_service.kafka.dto.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentKafkaProducer {
    private static final String TOPIC = "payment_events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public Mono<Void> sendPaymentCompletedEvent(PaymentCompletedEvent event) {
        return Mono.fromFuture(() -> kafkaTemplate.send(TOPIC, event.tripId(), event))
                   .doOnSuccess(result -> log.info("결제 완료 이벤트 발행 성공. Offset: {}, TripId: {}",
                           result.getRecordMetadata().offset(), event.tripId()))
                   .doOnError(ex -> log.error("결제 완료 이벤트 발행 실패. TripId: {}", event.tripId(), ex))
                   .then();
    }

    public Mono<Void> sendPaymentFailedEvent(PaymentFailedEvent event) {
        return Mono.fromFuture(() -> kafkaTemplate.send(TOPIC, event.tripId(), event))
                   .doOnSuccess(result -> log.warn("결제 실패 이벤트 발행 성공. TripId: {}", event.tripId()))
                   .doOnError(ex -> log.error("결제 실패 이벤트 발행 실패. TripId: {}", event.tripId(), ex))
                   .then();
    }
}