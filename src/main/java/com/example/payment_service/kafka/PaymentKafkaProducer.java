package com.example.payment_service.kafka;

import com.example.payment_service.kafka.dto.PaymentCompletedEvent;
import com.example.payment_service.kafka.dto.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentKafkaProducer {
    private static final String TOPIC = "payment_events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendPaymentCompletedEvent(PaymentCompletedEvent event) {
        log.info("결제 완료 이벤트 발행 -> topic: {}, tripId: {}", TOPIC, event.tripId());
        kafkaTemplate.send(TOPIC, event);
    }

    public void sendPaymentFailedEvent(PaymentFailedEvent event) {
        log.warn("결제 실패 이벤트 발행 -> topic: {}, tripId: {}", TOPIC, event.tripId());
        kafkaTemplate.send(TOPIC, event);
    }
}