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
            containerFactory = "dltKafkaListenerContainerFactory",
            concurrency = "3"
    )
    @Transactional
    public void consumeDlt(String message, Acknowledgment ack) {
        log.warn("[DLT] 최종 실패 메시지 수신: {}", message);

        String errorMessage;

        try {
            TripCompletedEvent event = objectMapper.readValue(message, TripCompletedEvent.class);
            errorMessage = "DLT 재처리 성공. 원인: 비즈니스 로직 실패";
            log.info("[DLT] 파싱 성공, 최종 실패 메시지 저장. Trip ID: {}", event.tripId());

        } catch (JsonProcessingException e) {
            errorMessage = "JSON 파싱 실패";
            log.error("[DLT] JSON 파싱 실패, 메시지 영구 저장: {}", message, e);
        }

        FailedEvent failedEvent = FailedEvent.builder()
                                             .payload(message)
                                             .topic("trip_events.DLT")
                                             .errorMessage(truncate(errorMessage, 500))
                                             .build();
        failedEventRepository.save(failedEvent);

        ack.acknowledge();
        log.info("[DLT] 메시지 처리 완료. 오프셋 커밋");
    }

    private String truncate(String message, int maxLen) {
        if (message == null) {
            return null;
        }
        return message.length() <= maxLen ? message : message.substring(0, maxLen);
    }
}