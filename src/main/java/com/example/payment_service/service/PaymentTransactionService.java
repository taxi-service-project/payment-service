package com.example.payment_service.service;

import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentOutbox;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentOutboxRepository;
import com.example.payment_service.repository.PaymentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final PaymentOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment createPendingPayment(TripCompletedEvent event, String userId, String paymentMethodId, Integer fare) {
        if (paymentRepository.existsByTripId(event.tripId())) {
            log.warn("이미 존재하는 결제 건입니다. TripID: {}", event.tripId());
            return paymentRepository.findByTripId(event.tripId()).orElseThrow();
        }

        Payment payment = Payment.builder()
                                 .tripId(event.tripId())
                                 .userId(userId)
                                 .paymentMethodId(paymentMethodId)
                                 .amount(fare)
                                 .build();

        Payment savedPayment = paymentRepository.save(payment);
        log.info("결제 요청 기록 저장(PENDING). Payment ID: {}", savedPayment.getId());
        return savedPayment;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment completePaymentWithOutbox(Long paymentId, String pgTransactionId, Object eventData) {
        Payment payment = paymentRepository.findById(paymentId)
                                           .orElseThrow(() -> new RuntimeException("결제 정보 유실"));
        payment.complete(pgTransactionId);
        saveOutboxInTransaction(payment.getTripId(), "payment_events", eventData);
        log.info("결제 완료 및 Outbox 저장 성공. Payment ID: {}", paymentId);
        return payment;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failPaymentWithOutbox(Long paymentId, String errorMessage, Object eventData) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        payment.fail();
        saveOutboxInTransaction(payment.getTripId(), "payment_events", eventData);
        log.warn("결제 실패 처리 및 Outbox 저장. Payment ID: {}, Reason: {}", paymentId, errorMessage);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryStartProcessing(Long paymentId) {
        // REQUESTED 상태인 것만 PROCESSING으로 변경 시도
        int updatedCount = paymentRepository.tryStartProcessing(paymentId);

        if (updatedCount > 0) {
            log.info("결제 처리 권한 획득 (PROCESSING 상태로 변경). Payment ID: {}", paymentId);
            return true; // 선점 성공
        } else {
            log.warn("이미 처리 중이거나 완료된 결제입니다. 선점 실패. Payment ID: {}", paymentId);
            return false; // 선점 실패
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsUnknown(Long paymentId, String pgTxId) {
        try {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            payment.unknown(pgTxId);
            log.error("🛑 수기 정산 필요 (UNKNOWN 상태). PaymentId: {}, PgTxId: {}", paymentId, pgTxId);
        } catch (Exception e) {
            log.error("MarkAsUnknown 실패", e);
        }
    }

    private void saveOutboxInTransaction(String aggregateId, String topic, Object eventData) {
        try {
            String payload = objectMapper.writeValueAsString(eventData);
            PaymentOutbox outbox = PaymentOutbox.builder()
                                                .aggregateId(aggregateId)
                                                .topic(topic)
                                                .payload(payload)
                                                .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 변환 실패", e);
        }
    }
}