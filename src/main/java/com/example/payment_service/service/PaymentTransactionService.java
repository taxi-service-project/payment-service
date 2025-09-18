package com.example.payment_service.service;

import com.example.payment_service.client.UserServiceClient;
import com.example.payment_service.client.VirtualPGClient;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final VirtualPGClient virtualPGClient;

    @Transactional
    public Payment saveAndProcessPayment(TripCompletedEvent event, UserServiceClient.UserInfoForPaymentResponse userInfo, Integer fare) {
        Payment payment = Payment.builder()
                                 .tripId(event.tripId())
                                 .userId(userInfo.userId())
                                 .paymentMethodId(userInfo.paymentMethodId())
                                 .amount(fare)
                                 .build();
        paymentRepository.save(payment);
        log.info("결제 요청 기록 저장 완료. Payment ID: {}", payment.getId());

        try {
            virtualPGClient.processPayment();
            payment.complete("dummy-tx-" + UUID.randomUUID());
            log.info("결제 완료 처리. Payment ID: {}", payment.getId());
        } catch (Exception e) {
            payment.fail();
            log.warn("가상 결제 처리 실패. Payment ID: {}", payment.getId(), e);
            paymentRepository.save(payment); // 실패 상태도 DB에 저장
            throw new RuntimeException(e);
        }
        return paymentRepository.save(payment);
    }
}