package com.example.payment_service.service;

import com.example.payment_service.client.PricingServiceClient;
import com.example.payment_service.client.UserServiceClient;
import com.example.payment_service.client.VirtualPGClient;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.exception.PaymentNotFoundException;
import com.example.payment_service.kafka.PaymentKafkaProducer;
import com.example.payment_service.kafka.dto.PaymentCompletedEvent;
import com.example.payment_service.kafka.dto.PaymentFailedEvent;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PricingServiceClient pricingServiceClient;
    private final UserServiceClient userServiceClient;
    private final PaymentKafkaProducer kafkaProducer;
    private final VirtualPGClient virtualPGClient;

    private final PaymentTransactionService paymentTransactionService;

    public Mono<Void> processPayment(TripCompletedEvent event) {

        Mono<PricingServiceClient.FareResponse> fareMono = pricingServiceClient.calculateFare(
                event.tripId(), event.distanceMeters(), event.durationSeconds(), event.endedAt());

        Mono<UserServiceClient.UserInfoForPaymentResponse> userInfoMono = userServiceClient.getUserInfoForPayment(event.userId());

        return Mono.zip(fareMono, userInfoMono)
                   .flatMap(tuple -> {
                       Integer fare = tuple.getT1().fare();
                       var userInfo = tuple.getT2();

                       if (fare < 0) {
                           log.warn("폴백 요금 감지. Trip ID: {}. 결제 중단.", event.tripId());
                           return Mono.error(new RuntimeException("가격 서비스 폴백 트리거됨"));
                       }

                       return Mono.fromCallable(() ->
                                          paymentTransactionService.createPendingPayment(
                                                  event, userInfo.userId(), userInfo.paymentMethodId(), fare
                                          )
                                  )
                                  .subscribeOn(Schedulers.boundedElastic())
                                  .flatMap(payment -> processPgAndComplete(payment));
                   })
                   // Kafka 이벤트 발행 (성공 시)
                   .doOnSuccess(payment -> {
                       if (payment != null && payment.getStatus() == PaymentStatus.COMPLETED) {
                           PaymentCompletedEvent paymentEvent = new PaymentCompletedEvent(
                                   payment.getTripId(), payment.getAmount(), payment.getUserId());
                           kafkaProducer.sendPaymentCompletedEvent(paymentEvent);
                       }
                   })
                   // Kafka 이벤트 발행 (최종 실패 시)
                   .onErrorResume(error -> {
                       log.error("결제 파이프라인 최종 실패. Trip ID: {}", event.tripId(), error);
                       PaymentFailedEvent failedEvent = new PaymentFailedEvent(event.tripId(), error.getMessage());
                       kafkaProducer.sendPaymentFailedEvent(failedEvent);
                       return Mono.empty();
                   })
                   .then();
    }

    private Mono<Payment> processPgAndComplete(Payment payment) {
        return Mono.fromCallable(() -> {
            try {
                virtualPGClient.processPayment();
                return paymentTransactionService.updatePaymentStatus(payment.getId(), true, null);

            } catch (Exception e) {
                log.warn("PG 결제 실패. Payment ID: {}", payment.getId(), e);
                paymentTransactionService.updatePaymentStatus(payment.getId(), false, e.getMessage());
                throw new RuntimeException("PG 결제 실패", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()); // PG 호출 및 DB 업데이트 블로킹 격리
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByTripId(String tripId) {
        Payment payment = paymentRepository.findByTripId(tripId)
                                           .orElseThrow(() -> new PaymentNotFoundException("결제 내역 미발견: " + tripId));
        return PaymentResponse.fromEntity(payment);
    }
}