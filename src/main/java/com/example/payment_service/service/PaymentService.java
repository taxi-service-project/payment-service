package com.example.payment_service.service;

import com.example.payment_service.client.PricingServiceClient;
import com.example.payment_service.client.UserServiceClient;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.exception.PaymentNotFoundException;
import com.example.payment_service.kafka.PaymentKafkaProducer;
import com.example.payment_service.kafka.dto.PaymentCompletedEvent;
import com.example.payment_service.kafka.dto.PaymentFailedEvent;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import jakarta.annotation.PostConstruct;
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
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final PaymentTransactionService paymentTransactionService;

    private CircuitBreaker pricingCircuitBreaker;
    private CircuitBreaker userCircuitBreaker;

    @PostConstruct
    public void init() {
        pricingCircuitBreaker = circuitBreakerRegistry.circuitBreaker("pricing-service");
        userCircuitBreaker = circuitBreakerRegistry.circuitBreaker("user-service");
    }

    public Mono<Void> processPayment(TripCompletedEvent event) {

        Mono<PricingServiceClient.FareResponse> fareMono = pricingServiceClient.calculateFare(
                                                                                       event.tripId(), event.distanceMeters(), event.durationSeconds(), event.endedAt())
                                                                               .transform(CircuitBreakerOperator.of(pricingCircuitBreaker));

        Mono<UserServiceClient.UserInfoForPaymentResponse> userInfoMono = userServiceClient.getUserInfoForPayment(event.userId())
                                                                                           .transform(CircuitBreakerOperator.of(userCircuitBreaker));

        return Mono.zip(fareMono, userInfoMono)
                   .flatMap(tuple -> {
                       Integer fare = tuple.getT1().fare();
                       UserServiceClient.UserInfoForPaymentResponse userInfo = tuple.getT2();

                       // 분리된 Transactional 서비스의 메소드를 호출
                       return Mono.fromCallable(() -> paymentTransactionService.saveAndProcessPayment(event, userInfo, fare))
                                  .subscribeOn(Schedulers.boundedElastic());
                   })
                   .doOnSuccess(payment -> {
                       if (payment != null && payment.getStatus() == PaymentStatus.COMPLETED) {
                           PaymentCompletedEvent paymentEvent = new PaymentCompletedEvent(payment.getTripId(), payment.getAmount(), payment.getUserId());
                           kafkaProducer.sendPaymentCompletedEvent(paymentEvent);
                       }
                   })
                   .onErrorResume(error -> {
                       log.error("결제 처리 파이프라인 최종 오류 발생. Trip ID: {}", event.tripId(), error);
                       PaymentFailedEvent failedEvent = new PaymentFailedEvent(event.tripId(), error.getMessage());
                       kafkaProducer.sendPaymentFailedEvent(failedEvent);
                       return Mono.empty();
                   })
                   .then();
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByTripId(String tripId) {
        Payment payment = paymentRepository.findByTripId(tripId)
                                           .orElseThrow(() -> new PaymentNotFoundException("해당 tripId의 결제 내역을 찾을 수 없습니다: " + tripId));
        return PaymentResponse.fromEntity(payment);
    }
}