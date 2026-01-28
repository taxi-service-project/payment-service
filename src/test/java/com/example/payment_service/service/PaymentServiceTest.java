package com.example.payment_service.service;

import com.example.payment_service.client.PricingServiceClient;
import com.example.payment_service.client.UserServiceClient;
import com.example.payment_service.client.VirtualPGClient;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.exception.PricingServiceUnavailableException;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentOutboxRepository;
import com.example.payment_service.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private PaymentService paymentService;

    @Mock private PaymentRepository paymentRepository;
    @Mock private PricingServiceClient pricingServiceClient;
    @Mock private UserServiceClient userServiceClient;
    @Mock private VirtualPGClient virtualPGClient;
    @Mock private PaymentOutboxRepository outboxRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private PaymentTransactionService paymentTransactionService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository, pricingServiceClient, userServiceClient,
                virtualPGClient, outboxRepository, objectMapper, paymentTransactionService
        );
    }

    private Payment createMockPayment(Long id, PaymentStatus status) {
        Payment payment = Payment.builder()
                                 .tripId("trip-1")
                                 .userId("user-1")
                                 .paymentMethodId("card-1")
                                 .amount(5000)
                                 .build();
        ReflectionTestUtils.setField(payment, "id", id);
        ReflectionTestUtils.setField(payment, "status", status);
        return payment;
    }

    @Test
    @DisplayName("✅ 정상 흐름: 요금계산 -> 결제생성 -> 선점(Lock) -> PG호출 -> 완료처리(Outbox)")
    void processPayment_Success() {
        // Given
        TripCompletedEvent event = new TripCompletedEvent("trip-1", "user-1", 1000, 600, LocalDateTime.now());

        given(pricingServiceClient.calculateFare(anyString(), anyInt(), anyInt(), any()))
                .willReturn(Mono.just(new PricingServiceClient.FareResponse(5000)));
        given(userServiceClient.getUserInfoForPayment(anyString()))
                .willReturn(Mono.just(new UserServiceClient.UserInfoForPaymentResponse("user-1", "name", "email", "phone", "card-123")));

        Payment requestedPayment = createMockPayment(1L, PaymentStatus.REQUESTED);
        given(paymentTransactionService.createPendingPayment(any(), anyString(), anyString(), anyInt()))
                .willReturn(requestedPayment);

        // 🚨 핵심: 선점 성공 (tryStartProcessing -> true)
        given(paymentTransactionService.tryStartProcessing(1L))
                .willReturn(true);

        // PG 성공
        String pgTxId = "tx_12345";
        given(virtualPGClient.processPayment()).willReturn(pgTxId);

        // 최종 완료 (Outbox 저장 포함)
        Payment completedPayment = createMockPayment(1L, PaymentStatus.COMPLETED);
        given(paymentTransactionService.completePaymentWithOutbox(eq(1L), eq(pgTxId), any()))
                .willReturn(completedPayment);

        // When
        Mono<Void> result = paymentService.processPayment(event);

        // Then
        StepVerifier.create(result)
                    .verifyComplete();

        then(virtualPGClient).should(times(1)).processPayment();
        then(paymentTransactionService).should(times(1)).completePaymentWithOutbox(eq(1L), eq(pgTxId), any());
    }

    @Test
    @DisplayName("⛔ 동시성 방어: 이미 다른 스레드가 선점했다면(false), PG 호출 없이 조용히 종료된다")
    void processPayment_Concurrency_AlreadyProcessing() {
        // Given
        TripCompletedEvent event = new TripCompletedEvent("trip-1", "user-1", 1000, 600, LocalDateTime.now());

        given(pricingServiceClient.calculateFare(any(), any(), any(), any()))
                .willReturn(Mono.just(new PricingServiceClient.FareResponse(5000)));
        given(userServiceClient.getUserInfoForPayment(any()))
                .willReturn(Mono.just(new UserServiceClient.UserInfoForPaymentResponse("user-1", "name", "email", "phone", "card-123")));

        Payment requestedPayment = createMockPayment(1L, PaymentStatus.REQUESTED);
        given(paymentTransactionService.createPendingPayment(any(), anyString(), anyString(), anyInt()))
                .willReturn(requestedPayment);

        // 🚨 핵심: 선점 실패 (tryStartProcessing -> false)
        given(paymentTransactionService.tryStartProcessing(1L))
                .willReturn(false);

        // When
        Mono<Void> result = paymentService.processPayment(event);

        // Then
        StepVerifier.create(result)
                    .verifyComplete();

        // PG 호출은 절대 일어나면 안 됨!
        then(virtualPGClient).should(never()).processPayment();
        // 완료 처리도 일어나면 안 됨!
        then(paymentTransactionService).should(never()).completePaymentWithOutbox(any(), any(), any());
    }

    @Test
    @DisplayName("❌ PG 실패 시: failPaymentWithOutbox가 호출되어야 한다")
    void processPayment_PgFailure() {
        // Given
        TripCompletedEvent event = new TripCompletedEvent("trip-1", "user-1", 1000, 600, LocalDateTime.now());

        given(pricingServiceClient.calculateFare(any(), any(), any(), any()))
                .willReturn(Mono.just(new PricingServiceClient.FareResponse(5000)));
        given(userServiceClient.getUserInfoForPayment(any()))
                .willReturn(Mono.just(new UserServiceClient.UserInfoForPaymentResponse("user-1", "name", "email", "phone", "card-123")));

        Payment requestedPayment = createMockPayment(1L, PaymentStatus.REQUESTED);
        given(paymentTransactionService.createPendingPayment(any(), anyString(), anyString(), anyInt()))
                .willReturn(requestedPayment);
        given(paymentTransactionService.tryStartProcessing(1L)).willReturn(true);

        // 🚨 PG 에러 발생
        given(virtualPGClient.processPayment()).willThrow(new RuntimeException("PG Error"));

        // When
        Mono<Void> result = paymentService.processPayment(event);

        // Then
        StepVerifier.create(result)
                    .verifyComplete(); // onErrorResume에서 잡아서 처리하므로 Complete

        // fail 메서드 호출 확인
        then(paymentTransactionService).should().failPaymentWithOutbox(eq(1L), eq("PG 승인 거절"), any());
    }

    @Test
    @DisplayName("🔄 재시도: 가격 서비스 장애(UnavailableException) 시 Kafka 재시도를 위해 에러를 던져야 한다")
    void processPayment_PricingServiceError_ShouldRetry() {
        // Given
        TripCompletedEvent event = new TripCompletedEvent("trip-1", "user-1", 1000, 600, LocalDateTime.now());

        // 🚨 가격 서비스 장애 발생 (Retryable Error)
        given(pricingServiceClient.calculateFare(any(), any(), any(), any()))
                .willReturn(Mono.error(new PricingServiceUnavailableException("Service Down")));

        given(userServiceClient.getUserInfoForPayment(any()))
                .willReturn(Mono.just(new UserServiceClient.UserInfoForPaymentResponse("user-1", "name", "email", "phone", "card-123")));

        // When
        Mono<Void> result = paymentService.processPayment(event);

        // Then
        StepVerifier.create(result)
                    .expectError(PricingServiceUnavailableException.class) // 에러가 밖으로 던져져야 함!
                    .verify();

        // 결제 생성 로직까지 가면 안 됨
        then(paymentTransactionService).should(never()).createPendingPayment(any(), any(), any(), any());
    }
}