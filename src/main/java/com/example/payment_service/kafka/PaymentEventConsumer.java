package com.example.payment_service.kafka;

import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {
    private final PaymentService paymentService;

    @KafkaListener(topics = "trip_events", groupId = "payment-service-group", containerFactory = "kafkaListenerContainerFactory")
    public Mono<Void> handleTripCompletedEvent(TripCompletedEvent event, Acknowledgment ack) {
        log.info("운행 완료 이벤트 수신. Trip ID: {}", event.tripId());

        return paymentService.processPayment(event)
                             .doOnSuccess(result -> {
                                 log.info("결제 처리 성공. 오프셋 커밋");
                                 ack.acknowledge();
                             })
                             .doOnError(e -> {
                                 // 오류가 발생하면 DefaultErrorHandler가 처리
                                 log.error("결제 처리 중 오류 발생. event: {}", event, e);
                             });
    }
}