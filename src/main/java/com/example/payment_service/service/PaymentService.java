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
    private final PaymentTransactionService paymentTransactionService;

    public Mono<Void> processPayment(TripCompletedEvent event) {

        Mono<PricingServiceClient.FareResponse> fareMono = pricingServiceClient.calculateFare(
                event.tripId(), event.distanceMeters(), event.durationSeconds(), event.endedAt());

        Mono<UserServiceClient.UserInfoForPaymentResponse> userInfoMono = userServiceClient.getUserInfoForPayment(event.userId());

        return Mono.zip(fareMono, userInfoMono)
                   .flatMap(tuple -> {
                       Integer fare = tuple.getT1().fare();
                       UserServiceClient.UserInfoForPaymentResponse userInfo = tuple.getT2();

                       if (fare < 0) {
                           log.warn("폴백(Fallback) 요금을 수신했습니다. Trip ID: {}. 결제를 중단합니다.", event.tripId());
                           return Mono.error(new RuntimeException("가격 조회 서비스 폴백이 트리거되었습니다."));
                       }

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
                       log.error("결제 처리 파이프라인에서 최종 오류가 발생했습니다. Trip ID: {}", event.tripId(), error);
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