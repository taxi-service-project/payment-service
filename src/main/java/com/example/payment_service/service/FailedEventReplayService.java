package com.example.payment_service.service;

import com.example.payment_service.entity.FailedEvent;
import com.example.payment_service.entity.FailedEventStatus;
import com.example.payment_service.repository.FailedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class FailedEventReplayService {

    private final FailedEventRepository failedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    private static final int CHUNK_SIZE = 1000;

    public int retryAllByTopic(String targetTopic) {
        int totalProcessed = 0;
        boolean hasNext = true;

        log.info("🚀 [Bulk Retry] 토픽({}) 재발행 시작...", targetTopic);

        while (hasNext) {
            Pageable pageable = PageRequest.of(0, CHUNK_SIZE, Sort.by(Sort.Direction.ASC, "id"));
            Slice<FailedEvent> slice = failedEventRepository.findAllByTopicAndStatus(
                    targetTopic,
                    FailedEventStatus.PENDING,
                    pageable
            );

            List<FailedEvent> events = slice.getContent();
            if (events.isEmpty()) break;

            List<Long> successIds = new ArrayList<>();

            for (FailedEvent event : events) {
                try {
                    if (event.getKafkaKey() != null) {
                        kafkaTemplate.send(event.getTopic(), event.getKafkaKey(), event.getPayload()).get();
                    } else {
                        kafkaTemplate.send(event.getTopic(), event.getPayload()).get();
                    }

                    successIds.add(event.getId());

                } catch (Exception e) {
                    log.error("❌ 재발행 개별 실패 (ID: {}). 건너뜁니다.", event.getId(), e);
                }
            }

            if (!successIds.isEmpty()) {
                transactionTemplate.execute(status -> {
                    failedEventRepository.updateStatus(successIds, FailedEventStatus.RESOLVED);
                    return null;
                });
                totalProcessed += successIds.size();
            }

            if (successIds.isEmpty()) {
                log.warn("⚠️ 이번 청크에서 재발행 성공한 건이 없습니다. 무한 루프 방지를 위해 중단합니다.");
                break;
            }

            hasNext = slice.hasNext();
        }

        log.info("✅ [Bulk Retry] 완료. 총 {}건 재발행됨.", totalProcessed);
        return totalProcessed;
    }

    @Transactional
    public void ignoreEvent(Long eventId) {
        FailedEvent event = failedEventRepository.findById(eventId)
                                                 .orElseThrow(() -> new IllegalArgumentException("이벤트 없음: " + eventId));

        if (event.getStatus() != FailedEventStatus.PENDING) {
            throw new IllegalStateException("이미 처리된 이벤트입니다.");
        }

        failedEventRepository.updateStatus(List.of(eventId), FailedEventStatus.IGNORED);
        log.info("🗑️ 에러 메시지(ID: {}) 폐기 완료.", eventId);
    }

}