package com.example.payment_service.service;

import com.example.payment_service.client.PricingServiceClient;
import com.example.payment_service.client.UserServiceClient;
import com.example.payment_service.client.VirtualPGClient;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.kafka.PaymentKafkaProducer;
import com.example.payment_service.kafka.dto.PaymentCompletedEvent;
import com.example.payment_service.kafka.dto.PaymentFailedEvent;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentRepository;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private PaymentService paymentService;

    @Mock private PaymentRepository paymentRepository;
    @Mock private PricingServiceClient pricingServiceClient;
    @Mock private UserServiceClient userServiceClient;
    @Mock private PaymentKafkaProducer kafkaProducer;
    @Mock private VirtualPGClient virtualPGClient;
    @Mock private PaymentTransactionService paymentTransactionService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository, pricingServiceClient, userServiceClient,
                kafkaProducer, virtualPGClient, paymentTransactionService
        );
    }

    // Helper: 엔티티 Mocking용
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

    // Helper: DTO 생성
    private UserServiceClient.UserInfoForPaymentResponse createUserInfo(String userId, String paymentMethodId) {
        return new UserServiceClient.UserInfoForPaymentResponse(
                userId,
                "dummy-name",
                "dummy-email",
                "dummy-phone",
                paymentMethodId
        );
    }

    @Test
    @DisplayName("정상 흐름: 요금계산 -> 결제생성(REQUESTED) -> PG호출 -> 완료처리(COMPLETED) -> Kafka발행")
    void processPayment_Success() throws Exception {
        // Given
        TripCompletedEvent event = new TripCompletedEvent("trip-1", "user-1", 1000, 600, LocalDateTime.now());

        given(pricingServiceClient.calculateFare(anyString(), anyInt(), anyInt(), any()))
                .willReturn(Mono.just(new PricingServiceClient.FareResponse(5000)));

        var userInfo = new UserServiceClient.UserInfoForPaymentResponse("user-1", "name", "email", "phone", "card-123");

        given(userServiceClient.getUserInfoForPayment(anyString()))
                .willReturn(Mono.just(userInfo));

        Payment requestedPayment = createMockPayment(1L, PaymentStatus.REQUESTED);
        given(paymentTransactionService.createPendingPayment(any(), anyString(), anyString(), anyInt()))
                .willReturn(requestedPayment);

        Payment completedPayment = createMockPayment(1L, PaymentStatus.COMPLETED);
        given(paymentTransactionService.updatePaymentStatus(eq(1L), eq(true), any()))
                .willReturn(completedPayment);

        // Kafka
        given(kafkaProducer.sendPaymentCompletedEvent(any(PaymentCompletedEvent.class)))
                .willReturn(Mono.empty());

        // When
        Mono<Void> result = paymentService.processPayment(event);

        // Then
        StepVerifier.create(result)
                    .verifyComplete();

        // verify 단계에서 체크 예외 처리가 필요함 -> 메서드 시그니처에 throws Exception 추가로 해결
        then(virtualPGClient).should(times(1)).processPayment();
        then(kafkaProducer).should(times(1)).sendPaymentCompletedEvent(any(PaymentCompletedEvent.class));
    }

    @Test
    @DisplayName("PG 결제 실패 시: 상태를 FAILED로 변경하고 실패 이벤트를 발행해야 한다")
    void processPayment_PgFailure_ShouldHandleError() throws Exception { // [수정] throws Exception 추가
        // Given
        TripCompletedEvent event = new TripCompletedEvent("trip-1", "user-1", 1000, 600, LocalDateTime.now());

        given(pricingServiceClient.calculateFare(anyString(), anyInt(), anyInt(), any()))
                .willReturn(Mono.just(new PricingServiceClient.FareResponse(5000)));

        var userInfo = new UserServiceClient.UserInfoForPaymentResponse("user-1", "name", "email", "phone", "card-123");

        given(userServiceClient.getUserInfoForPayment(anyString()))
                .willReturn(Mono.just(userInfo));

        Payment requestedPayment = createMockPayment(1L, PaymentStatus.REQUESTED);
        given(paymentTransactionService.createPendingPayment(any(), anyString(), anyString(), anyInt()))
                .willReturn(requestedPayment);

        // PG Error
        doThrow(new RuntimeException("PG Connection Timeout"))
                .when(virtualPGClient).processPayment();

        // Update Status to FAILED
        Payment failedPayment = createMockPayment(1L, PaymentStatus.FAILED);
        given(paymentTransactionService.updatePaymentStatus(eq(1L), eq(false), anyString()))
                .willReturn(failedPayment);

        // Kafka Failed Event
        given(kafkaProducer.sendPaymentFailedEvent(any(PaymentFailedEvent.class)))
                .willReturn(Mono.empty());

        // When
        Mono<Void> result = paymentService.processPayment(event);

        // Then
        StepVerifier.create(result)
                    .verifyComplete();

        then(paymentTransactionService).should().updatePaymentStatus(eq(1L), eq(false), contains("PG Connection Timeout"));
        then(kafkaProducer).should().sendPaymentFailedEvent(any(PaymentFailedEvent.class));
    }
}