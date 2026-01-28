package com.example.payment_service.service;

import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentOutbox;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentOutboxRepository;
import com.example.payment_service.repository.PaymentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionServiceTest {

    @InjectMocks
    private PaymentTransactionService transactionService;

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentOutboxRepository outboxRepository;
    @Mock private ObjectMapper objectMapper;

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
    @DisplayName("결제 생성: 최초 요청이면 REQUESTED 상태로 저장되어야 한다")
    void createPendingPayment_New() {
        // Given
        TripCompletedEvent event = new TripCompletedEvent("trip-1", "user-1", 1000, 500, LocalDateTime.now());

        given(paymentRepository.existsByTripId("trip-1")).willReturn(false); // 존재하지 않음

        given(paymentRepository.save(any(Payment.class))).willAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 1L);
            return p;
        });

        // When
        Payment result = transactionService.createPendingPayment(event, "user-1", "card-1", 5000);

        // Then
        then(paymentRepository).should().save(any(Payment.class));
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    @DisplayName("결제 생성: 이미 존재하는 결제라면 저장하지 않고 기존 건을 반환해야 한다 (멱등성)")
    void createPendingPayment_Duplicate() {
        // Given
        TripCompletedEvent event = new TripCompletedEvent("trip-1", "user-1", 1000, 500, LocalDateTime.now());
        Payment existingPayment = createMockPayment(1L, PaymentStatus.REQUESTED);

        given(paymentRepository.existsByTripId("trip-1")).willReturn(true); // 이미 존재함
        given(paymentRepository.findByTripId("trip-1")).willReturn(Optional.of(existingPayment));

        // When
        Payment result = transactionService.createPendingPayment(event, "user-1", "card-1", 5000);

        // Then
        then(paymentRepository).should(never()).save(any(Payment.class)); // save 호출 안 함!
        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("선점 시도: DB 업데이트가 1건이면 true를 반환한다")
    void tryStartProcessing_Success() {
        // Given
        given(paymentRepository.tryStartProcessing(1L)).willReturn(1);

        // When
        boolean result = transactionService.tryStartProcessing(1L);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("선점 시도: DB 업데이트가 0건이면 false를 반환한다")
    void tryStartProcessing_Fail() {
        // Given
        given(paymentRepository.tryStartProcessing(1L)).willReturn(0);

        // When
        boolean result = transactionService.tryStartProcessing(1L);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("결제 완료: 상태를 COMPLETED로 변경하고 Outbox에 이벤트를 저장해야 한다")
    void completePaymentWithOutbox_Success() throws JsonProcessingException {
        // Given
        Payment payment = createMockPayment(1L, PaymentStatus.PROCESSING);
        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        given(objectMapper.writeValueAsString(any())).willReturn("{\"json\":\"payload\"}");

        // When
        Payment result = transactionService.completePaymentWithOutbox(1L, "pg_tx_123", new Object());

        // Then
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.getPgTransactionId()).isEqualTo("pg_tx_123");

        // Outbox 저장 검증
        then(outboxRepository).should(times(1)).save(any(PaymentOutbox.class));
    }

    @Test
    @DisplayName("결제 실패: 상태를 FAILED로 변경하고 Outbox에 이벤트를 저장해야 한다")
    void failPaymentWithOutbox_Success() throws JsonProcessingException {
        // Given
        Payment payment = createMockPayment(1L, PaymentStatus.PROCESSING);
        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        given(objectMapper.writeValueAsString(any())).willReturn("{\"json\":\"payload\"}");

        // When
        transactionService.failPaymentWithOutbox(1L, "잔액 부족", new Object());

        // Then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        // Outbox 저장 검증
        then(outboxRepository).should(times(1)).save(any(PaymentOutbox.class));
    }

    @Test
    @DisplayName("Unknown 마킹: 상태를 UNKNOWN으로 변경해야 한다")
    void markAsUnknown_Success() {
        // Given
        Payment payment = createMockPayment(1L, PaymentStatus.PROCESSING);
        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));

        // When
        transactionService.markAsUnknown(1L, "pg_tx_123");

        // Then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(payment.getPgTransactionId()).isEqualTo("pg_tx_123");
    }
}