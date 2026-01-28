package com.example.payment_service.kafka;

import com.example.payment_service.entity.OutboxStatus;
import com.example.payment_service.entity.PaymentOutbox;
import com.example.payment_service.repository.PaymentOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentOutboxRelay {

    private final PaymentOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 500)
    public void publishEvents() {
        List<PaymentOutbox> eventsToPublish = transactionTemplate.execute(status -> {
            List<PaymentOutbox> events = outboxRepository.findEventsForPublishing(100);
            if (events.isEmpty()) return null;

            List<Long> ids = events.stream().map(PaymentOutbox::getId).toList();
            outboxRepository.updateStatus(ids, OutboxStatus.PUBLISHING);
            return events;
        });

        if (eventsToPublish == null || eventsToPublish.isEmpty()) return;

        for (PaymentOutbox event : eventsToPublish) {
            sendToKafka(event);
        }
    }

    private void sendToKafka(PaymentOutbox event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get();

            log.info("✅ [Payment-Outbox] 발행 성공 | ID: {} | Topic: {} | Key: {}",
                    event.getId(), event.getTopic(), event.getAggregateId());
            outboxRepository.updateStatus(List.of(event.getId()), OutboxStatus.DONE);

        } catch (Exception e) {
            log.error("❌ [Payment-Outbox] 발행 실패 | ID: {} | Topic: {} | Error: {}",
                    event.getId(), event.getTopic(), e.getMessage(), e);
            // 실패 시 다시 READY로
            updateStatusSingle(event.getId(), OutboxStatus.READY);
        }
    }

    private void updateStatusSingle(Long id, OutboxStatus status) {
        transactionTemplate.execute(tx -> {
            outboxRepository.updateStatus(List.of(id), status);
            return null;
        });
    }

    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "Payment_rescueStuckEvents", lockAtLeastFor = "PT30S", lockAtMostFor = "PT50S")
    public void rescueStuckEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        transactionTemplate.execute(status -> {
            int count = outboxRepository.resetStuckEvents(OutboxStatus.PUBLISHING, OutboxStatus.READY, cutoff);
            if (count > 0) log.warn("🚨 [Payment] Stuck 이벤트 {}건 복구 완료", count);
            return null;
        });
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "Payment_cleanupOldEvents", lockAtLeastFor = "PT30S", lockAtMostFor = "PT50S")
    public void cleanupOldEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(3);
        transactionTemplate.execute(status -> {
            int count = outboxRepository.deleteOldEvents(OutboxStatus.DONE, cutoff);
            if (count > 0) log.info("🧹 [Payment] 오래된 이벤트 {}건 삭제 완료", count);
            return null;
        });
    }
}