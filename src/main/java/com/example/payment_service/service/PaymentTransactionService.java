package com.example.payment_service.service;

import com.example.payment_service.entity.Payment;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment createPendingPayment(TripCompletedEvent event, String userId, String paymentMethodId, Integer fare) {
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
    public Payment updatePaymentStatus(Long paymentId, boolean isSuccess, String errorMessage) {
        Payment payment = paymentRepository.findById(paymentId)
                                           .orElseThrow(() -> new RuntimeException("결제 정보 유실 (ID: " + paymentId + ")"));

        if (isSuccess) {
            payment.complete("dummy-tx-" + UUID.randomUUID());
            log.info("결제 완료 처리(COMPLETED). Payment ID: {}", paymentId);
        } else {
            payment.fail();
            log.warn("결제 실패 처리(FAILED). Payment ID: {}, Reason: {}", paymentId, errorMessage);
        }

        return paymentRepository.save(payment);
    }
}