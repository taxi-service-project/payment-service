package com.example.payment_service.kafka;

import com.example.payment_service.entity.FailedEvent;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.FailedEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentEventDltConsumer {

    private final FailedEventRepository failedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "trip_events.DLT",
            groupId = "${spring.kafka.consumer.group-id}.dlt",
            containerFactory = "dltKafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeDlt(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String originalDltTopic,
            @Header(value = "kafka_dlt_exception_message", required = false) String exceptionMessage
    ) {
        log.warn("[DLT 수신] 토픽: {}, 메시지: {}", originalDltTopic, message);

        if (exceptionMessage == null) {
            exceptionMessage = "Unknown Error";
        }

        FailedEvent failedEvent = FailedEvent.builder()
                                             .topic(originalDltTopic)
                                             .payload(message)
                                             .errorMessage(truncate(exceptionMessage, 1000))
                                             .build();

        failedEventRepository.save(failedEvent);
    }

    private String truncate(String str, int max) {
        if (str == null) return "";
        return str.length() > max ? str.substring(0, max) : str;
    }
}