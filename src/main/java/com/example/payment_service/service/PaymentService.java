package com.example.payment_service.service;

import com.example.payment_service.client.PricingServiceClient;
import com.example.payment_service.client.UserServiceClient;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.exception.PaymentNotFoundException;
import com.example.payment_service.kafka.PaymentKafkaProducer;
import com.example.payment_service.kafka.dto.PaymentCompletedEvent;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PricingServiceClient pricingServiceClient;
    private final UserServiceClient userServiceClient;
    private final PaymentKafkaProducer kafkaProducer;


    @Transactional
    public Mono<Void> processPayment(TripCompletedEvent event) {
        Mono<PricingServiceClient.FareResponse> fareMono = pricingServiceClient.calculateFare(
                event.tripId(), event.distanceMeters(), event.durationSeconds(), event.endedAt());

        Mono<UserServiceClient.UserInfoForPaymentResponse> userInfoMono =
                userServiceClient.getUserInfoForPayment(event.userId());

        return Mono.zip(fareMono, userInfoMono)
                   .flatMap(tuple -> {
                       Integer fare = tuple.getT1().fare();
                       UserServiceClient.UserInfoForPaymentResponse userInfo = tuple.getT2();

                       Payment payment = Payment.builder()
                                                .tripId(event.tripId())
                                                .userId(userInfo.userId())
                                                .paymentMethodId(userInfo.paymentMethodId())
                                                .amount(fare)
                                                .build();

                       return Mono.fromCallable(() -> paymentRepository.save(payment))
                                  .subscribeOn(Schedulers.boundedElastic())
                                  .doOnSuccess(p -> log.info("결제 요청 기록 저장 완료. Payment ID: {}", p.getId()));
                   })
                   .delayElement(Duration.ofSeconds(2))
                   .flatMap(payment -> {
                       payment.complete("dummy-tx-" + UUID.randomUUID());
                       return Mono.fromCallable(() -> paymentRepository.save(payment))
                                  .subscribeOn(Schedulers.boundedElastic())
                                  .doOnSuccess(p -> log.info("결제 완료 처리. Payment ID: {}", p.getId()));
                   })
                   .doOnSuccess(payment -> {
                       if (payment != null) {
                           PaymentCompletedEvent paymentEvent = new PaymentCompletedEvent(payment.getTripId(), payment.getAmount());
                           kafkaProducer.sendPaymentCompletedEvent(paymentEvent);
                       }
                   })
                   .then();
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByTripId(String tripId) {
        log.info("tripId로 결제 내역 조회 시작. Trip ID: {}", tripId);
        Payment payment = paymentRepository.findByTripId(tripId)
                                           .orElseThrow(() -> new PaymentNotFoundException("해당 tripId의 결제 내역을 찾을 수 없습니다: " + tripId));

        log.info("결제 내역 조회 성공. Payment ID: {}", payment.getId());
        return PaymentResponse.fromEntity(payment);
    }

}