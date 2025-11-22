package com.example.payment_service.kafka;

import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {
    private final PaymentService paymentService;

    @KafkaListener(topics = "trip_events", groupId = "payment-service-group", containerFactory = "kafkaListenerContainerFactory")
    public void handleTripCompletedEvent(TripCompletedEvent event) {
        log.info("운행 완료 이벤트 수신. Trip ID: {}", event.tripId());

        paymentService.processPayment(event).block();

        log.info("결제 처리 완료.");
    }
}