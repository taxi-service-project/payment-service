package com.example.payment_service.service;

import com.example.payment_service.client.PricingServiceClient;
import com.example.payment_service.client.UserServiceClient;
import com.example.payment_service.client.VirtualPGClient;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentOutbox;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.exception.PaymentNotFoundException;
import com.example.payment_service.exception.PricingServiceUnavailableException;
import com.example.payment_service.kafka.dto.PaymentCompletedEvent;
import com.example.payment_service.kafka.dto.PaymentFailedEvent;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentOutboxRepository;
import com.example.payment_service.repository.PaymentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final VirtualPGClient virtualPGClient;
    private final PaymentOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    private final PaymentTransactionService paymentTransactionService;

    public Mono<Void> processPayment(TripCompletedEvent event) {

        Mono<PricingServiceClient.FareResponse> fareMono = pricingServiceClient.calculateFare(
                event.tripId(), event.distanceMeters(), event.durationSeconds(), event.endedAt());

        Mono<UserServiceClient.UserInfoForPaymentResponse> userInfoMono = userServiceClient.getUserInfoForPayment(event.userId());

        return Mono.zip(fareMono, userInfoMono)
                   .flatMap(tuple -> {
                       Integer fare = tuple.getT1().fare();
                       var userInfo = tuple.getT2();

                       return Mono.fromCallable(() ->
                                          paymentTransactionService.createPendingPayment(
                                                  event, userInfo.userId(), userInfo.paymentMethodId(), fare
                                          ))
                                  .subscribeOn(Schedulers.boundedElastic())

                                  // 선점(Locking) 시도
                                  .flatMap(payment -> {
                                      return Mono.fromCallable(() ->
                                                         paymentTransactionService.tryStartProcessing(payment.getId())
                                                 )
                                                 .subscribeOn(Schedulers.boundedElastic())
                                                 .flatMap(isMyTurn -> {
                                                     if (!isMyTurn) {
                                                         log.info("다른 스레드/서버가 이미 처리 중입니다. (PaymentID: {})", payment.getId());
                                                         return Mono.empty(); // 선점 실패 -> 종료
                                                     }
                                                     // 선점 성공 -> 다음 단계로 payment 전달
                                                     return Mono.just(payment);
                                                 });
                                  })
                                  .flatMap(payment -> processPgAndComplete(payment));
                   })
                   .then()
                   .onErrorResume(error -> {
                       // 재시도(Retry)가 필요한 에러인지 확인
                       if (isRetryable(error)) {
                           log.warn("일시적인 장애 발생. Kafka 재시도를 위해 에러를 전파합니다. Trip ID: {}, Error: {}", event.tripId(), error.getMessage());
                           return Mono.error(error); // 🚨 에러를 다시 던져서 Kafka Consumer가 재시도하게 함
                       }

                       log.error("결제 파이프라인 최종 실패. Trip ID: {}", event.tripId(), error);
                       PaymentFailedEvent failedEvent = new PaymentFailedEvent(event.tripId(), error.getMessage());
                       return saveToOutbox("payment_events", event.tripId(), failedEvent).then();
                   });
    }

    private boolean isRetryable(Throwable error) {
        return error instanceof PricingServiceUnavailableException
                || error instanceof java.net.ConnectException;
    }

    private Mono<Payment> processPgAndComplete(Payment payment) {
        return Mono.fromCallable(() -> {
            String pgTxId = null;

            // 1단계: PG 결제 시도
            try {
                pgTxId = virtualPGClient.processPayment();
            } catch (Exception e) {
                log.warn("PG 결제 승인 실패. Payment ID: {}", payment.getId());
                PaymentFailedEvent failedEvent = new PaymentFailedEvent(payment.getTripId(), "PG 승인 거절: " + e.getMessage());
                paymentTransactionService.failPaymentWithOutbox(payment.getId(), "PG 승인 거절", failedEvent);
                throw new RuntimeException("PG 결제 승인 실패", e);
            }

            // 2단계: DB 반영
            try {
                PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(
                        payment.getTripId(), payment.getAmount(), payment.getUserId());

                return paymentTransactionService.completePaymentWithOutbox(
                        payment.getId(), pgTxId, completedEvent);

            } catch (Exception e) {
                log.error("🚨 CRITICAL: PG 승인 후 DB 반영 실패! 자동 취소 시도. Payment ID: {}", payment.getId(), e);

                // 3단계: 보상 트랜잭션 (자동 환불)
                try {
                    virtualPGClient.cancelPayment(pgTxId);
                    log.info("✅ 자동 취소(환불) 성공.");
                    PaymentFailedEvent failedEvent = new PaymentFailedEvent(payment.getTripId(), "시스템 오류로 인한 자동 취소");
                    paymentTransactionService.failPaymentWithOutbox(payment.getId(), "자동 취소 완료", failedEvent);

                } catch (Exception refundEx) {
                    log.error("💀 DOUBLE FAULT: 환불마저 실패함! 수기 정산 필요.", refundEx);
                    paymentTransactionService.markAsUnknown(payment.getId(), pgTxId);
                }
                throw new RuntimeException("결제 처리 중 시스템 오류 발생 (Double Fault 가능성 있음)", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<PaymentOutbox> saveToOutbox(String topic, String key, Object event) {
        return Mono.fromCallable(() -> {
            try {
                String payload = objectMapper.writeValueAsString(event);
                PaymentOutbox outbox = PaymentOutbox.builder().aggregateId(key).topic(topic).payload(payload).build();
                return outboxRepository.save(outbox);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByTripId(String tripId) {
        Payment payment = paymentRepository.findByTripId(tripId)
                                           .orElseThrow(() -> new PaymentNotFoundException("결제 내역 미발견: " + tripId));
        return PaymentResponse.fromEntity(payment);
    }
}